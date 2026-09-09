package com.formsnap.app.data

import androidx.room.withTransaction
import com.formsnap.app.data.local.*
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.SchemaMismatchException
import com.formsnap.app.processing.*
import com.formsnap.app.review.StaleReviewException
import com.formsnap.app.validation.IssueCode
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomStructureRepository(private val database: FormSnapDatabase,private val recognizer: StructureRecognizer,
    private val clock: Clock=Clock.systemUTC()) : StructureRepository {
    private val dao=database.structureDao()
    override fun observe(taskId: String)=dao.observe(taskId).map { rows -> rows.map { StoredStructure(StructureCodec.decode(it.payload),it.confirmed,it.revision) } }

    suspend fun record(taskId: String,proposal: StructureProposal,confirmed: Boolean,discard: Boolean=false) = database.withTransaction {
        require(database.sourceDao().getSources(taskId).any { it.id==proposal.sourceId })
        val old=dao.get(taskId,proposal.sourceId)
        dao.save(PageStructureEntity(proposal.sourceId,taskId,StructureCodec.encode(proposal),confirmed,(old?.revision ?: 0)+1,discard))
        if(!confirmed && proposal.grid.outcome!=StructureOutcome.AUTO_ACCEPTED)database.qualityDao().savePage(PageResultEntity(proposal.sourceId,taskId,"STRUCTURE_REVIEW_REQUIRED",
            IssueCode.STRUCTURE_REVIEW_REQUIRED.name,"已找到表格，请确认表头、分割及合并区域。",clock.millis()))
    }

    suspend fun process(source: SourceDocument,discardHumanWork: Boolean): CandidatePage {
        var proposal=recognizer.inspect(source)
        val template=dao.template(source.taskId)?.takeIf { it.sourceId!=source.id }?.let { StructureCodec.decode(it.payload) }
        if(template!=null) {
            try {
                val aligned=recognizer.revise(source,TemplateAlignment.grid(template,proposal),proposal.text)
                if(!TemplateAlignment.sameHeaders(template.headerPaths,aligned.headerPaths))throw SchemaMismatchException()
                // Explicitly confirmed topology may be reused; value ambiguities remain separate issues.
                proposal=aligned.copy(grid=aligned.grid.copy(outcome=StructureOutcome.AUTO_ACCEPTED))
            } catch (_: SchemaMismatchException) {
                record(source.taskId,proposal.copy(grid=proposal.grid.copy(outcome=StructureOutcome.STRUCTURE_REVIEW_REQUIRED,
                    reasons=proposal.grid.reasons+"此页与已有表头或列结构不同，请核对。")),false,discardHumanWork)
                throw SchemaMismatchException()
            }
        }
        record(source.taskId,proposal,false,discardHumanWork)
        if(proposal.grid.outcome!=StructureOutcome.AUTO_ACCEPTED)throw StructureReviewException(proposal)
        return TableCandidateAssembler().candidates(proposal)
    }

    private suspend fun edited(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int): Pair<PageStructureEntity,StructureProposal> {
        val old=checkNotNull(dao.get(taskId,sourceId))
        if(old.revision!=revision)throw StaleReviewException()
        check(database.qualityDao().pages(taskId).none { it.state=="RUNNING" })
        val source=database.sourceDao().getSources(taskId).single { it.id==sourceId }
        check(source.status==SourceStatus.AVAILABLE.name)
        require(paths.size==grid.columns && paths.all { it.isNotEmpty() && it.all(String::isNotBlank) })
        val proposal=recognizer.revise(source.toDomain(),grid,StructureCodec.decode(old.payload).text)
        return old to proposal.copy(headerPaths=paths)
    }
    override suspend fun saveDraft(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int): StoredStructure {
        val (old,editedProposal)=edited(taskId,sourceId,grid,paths,revision)
        val proposal=editedProposal.copy(grid=editedProposal.grid.copy(outcome=StructureOutcome.STRUCTURE_REVIEW_REQUIRED))
        database.withTransaction {
            if(dao.get(taskId,sourceId)?.revision!=revision)throw StaleReviewException()
            check(database.qualityDao().pages(taskId).none { it.state=="RUNNING" })
            record(taskId,proposal,false,old.discardHumanWork)
            RoomQualityRepository(database).revalidate(taskId)
        }
        return StoredStructure(proposal,false,revision+1)
    }
    override suspend fun confirm(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int,discardHumanWork: Boolean) {
        val (old,proposal)=edited(taskId,sourceId,grid,paths,revision)
        val candidates=TableCandidateAssembler().candidates(proposal)
        database.withTransaction {
            if(dao.get(taskId,sourceId)?.revision!=revision)throw StaleReviewException()
            check(database.qualityDao().pages(taskId).none { it.state=="RUNNING" })
            val structured=RoomStructuredRepository(database)
            if(structured.getDataset(taskId)==null)structured.createSchema(taskId,candidates.headers.mapIndexed { i,name ->
                FieldDefinition(UUID.randomUUID().toString(),name,headerPath=candidates.headerPaths[i]) },configurationConfirmed=false)
            structured.replacePage(taskId,candidates,discardHumanWork || old.discardHumanWork)
            record(taskId,proposal,true,discardHumanWork)
            RoomQualityRepository(database).revalidate(taskId)
        }
    }
}
