package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.HumanWorkExistsException
import com.formsnap.app.processing.*
import com.formsnap.app.review.*
import com.formsnap.app.validation.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RoomStructureRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: FormSnapDatabase
    private lateinit var repository: RoomStructureRepository
    private lateinit var sources: RoomSourceRepository
    private val access=FakeSourceAccess()
    private val recognizer=FixtureRecognizer()
    @Before fun open() { context=ApplicationProvider.getApplicationContext(); context.deleteDatabase("structure.db"); reopen() }
    private fun reopen() {
        db=Room.databaseBuilder(context,FormSnapDatabase::class.java,"structure.db").build()
        repository=RoomStructureRepository(db,recognizer); sources=RoomSourceRepository(db,access)
    }
    @After fun close() { db.close(); context.deleteDatabase("structure.db") }
    private suspend fun seed(pages: Int=2): DigitizationTask {
        val task=RoomTaskRepository(db.taskDao()).createTask("结构确认")
        sources.addSources(task.id,(0 until pages).map { "content://test/structure-$it" })
        return task
    }
    @Test fun `first confirmation survives reopen then scaled shifted page reuses template and full quality chain`() = runTest {
        val task=seed()
        val runner=ProcessingRunner(db,recognizer)
        assertEquals(ProcessingSummary(0,0,1),runner.run(ProcessingRequest(task.id)))
        val draft=repository.observe(task.id).first().single()
        assertFalse(draft.confirmed)
        assertNull(RoomStructuredRepository(db).getDataset(task.id))
        assertEquals(TaskStatus.REVIEW_REQUIRED.name,db.taskDao().getTask(task.id)!!.status)
        db.close(); reopen()
        assertEquals(draft,repository.observe(task.id).first().single())
        val saved=repository.saveDraft(task.id,draft.proposal.sourceId,draft.proposal.grid,draft.proposal.headerPaths,draft.revision)
        assertEquals(ProcessingSummary(0,0,1),ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id)))
        assertEquals("Continue must retain saved edits and revision",saved,repository.observe(task.id).first().single())
        try { repository.confirm(task.id,draft.proposal.sourceId,draft.proposal.grid,draft.proposal.headerPaths,draft.revision); fail("Stale edit must be rejected") }
        catch(_: StaleReviewException) { }
        repository.confirm(task.id,saved.proposal.sourceId,saved.proposal.grid,saved.proposal.headerPaths,saved.revision)
        assertEquals(ProcessingSummary(1,0,0),ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id)))
        val stored=repository.observe(task.id).first()
        assertTrue(stored.all { it.confirmed })
        val quality=RoomQualityRepository(db)
        val data=quality.revalidate(task.id).dataset!!
        assertEquals(listOf("业务","编号"),data.schema.fields[0].headerPath)
        assertEquals(4,data.rows.size)
        assertNotEquals(data.rows[0].cells[0].source!!.region,data.rows[2].cells[0].source!!.region)
        quality.updateFields(task.id,data.schema.fields)
        assertEquals(4,quality.exportableDataset(task.id).rows.size)
        db.close(); reopen()
        assertEquals(data.schema.fields[0].headerPath,RoomStructuredRepository(db).getDataset(task.id)!!.schema.fields[0].headerPath)
    }
    @Test fun `different next page is schema mismatch and is never silently forced onto template`() = runTest {
        val task=seed(); recognizer.mismatch=true
        ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id))
        val first=repository.observe(task.id).first().single()
        repository.confirm(task.id,first.proposal.sourceId,first.proposal.grid,first.proposal.headerPaths,first.revision)
        assertEquals(ProcessingSummary(0,1,0),ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id)))
        assertEquals(2,RoomStructuredRepository(db).getDataset(task.id)!!.rows.size)
        assertTrue(RoomQualityRepository(db).revalidate(task.id).issues.any { it.code==IssueCode.SCHEMA_MISMATCH })
        assertFalse(repository.observe(task.id).first().last().confirmed)
    }
    @Test fun `new body merge on a later page still requires an individual structure decision`() = runTest {
        val task=seed()
        ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id))
        val first=repository.observe(task.id).first().single()
        repository.confirm(task.id,first.proposal.sourceId,first.proposal.grid,first.proposal.headerPaths,first.revision)
        recognizer.newBodyMerge=true
        assertEquals(ProcessingSummary(0,0,1),ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id)))
        assertEquals(2,RoomStructuredRepository(db).getDataset(task.id)!!.rows.size)
        assertFalse(repository.observe(task.id).first().last().confirmed)
    }
    @Test fun `structure replacement cannot discard human values without explicit permission and source removal cascades`() = runTest {
        val task=seed(1)
        ProcessingRunner(db,recognizer).run(ProcessingRequest(task.id))
        val first=repository.observe(task.id).first().single()
        repository.confirm(task.id,first.proposal.sourceId,first.proposal.grid,first.proposal.headerPaths,first.revision)
        val cell=RoomStructuredRepository(db).getDataset(task.id)!!.rows[0].cells[0]
        RoomQualityRepository(db).decide(task.id,ReviewDecision(IssueTarget.CELL,cell.id,ReviewAction.EDIT,"MANUAL"))
        val confirmed=repository.observe(task.id).first().single()
        try { repository.confirm(task.id,first.proposal.sourceId,first.proposal.grid,first.proposal.headerPaths,confirmed.revision); fail("Human data must survive") }
        catch(_: HumanWorkExistsException) { }
        assertEquals("MANUAL",RoomStructuredRepository(db).getDataset(task.id)!!.rows[0].cells[0].confirmedValue)
        repository.confirm(task.id,first.proposal.sourceId,first.proposal.grid,first.proposal.headerPaths,confirmed.revision,discardHumanWork=true)
        val next=repository.observe(task.id).first().single()
        RoomQualityRepository(db).decide(task.id,ReviewDecision(IssueTarget.CELL,cell.id,ReviewAction.EDIT,"NEW-MANUAL"))
        try { repository.confirm(task.id,next.proposal.sourceId,next.proposal.grid,next.proposal.headerPaths,next.revision); fail("Previous override is not permanent consent") }
        catch(_: HumanWorkExistsException) { }
        sources.removeSource(task.id,first.proposal.sourceId)
        assertTrue(repository.observe(task.id).first().isEmpty())
        assertNull(db.structureDao().template(task.id))
    }

    private class FixtureRecognizer : StructureRecognizer {
        var mismatch=false
        var newBodyMerge=false
        private val image=GrayImage(1200,800,ByteArray(1200*800){255.toByte()})
        override suspend fun inspect(source: SourceDocument): StructureProposal {
            val delta=if(source.pageIndex==0)0 else 30
            val scale=if(source.pageIndex==0)1f else 1.1f
            val grid=TableGrid(listOf(60,250,500).map { (it*scale).toInt()+delta },listOf(50,130,210,290,370).map { (it*scale).toInt()+delta },1200,800,
                merged=listOf(GridCell(0,0,1,2)) + if(newBodyMerge && source.pageIndex>0)listOf(GridCell(2,0,2,1))else emptyList(),
                headerEnd=2,outcome=StructureOutcome.STRUCTURE_REVIEW_REQUIRED,reasons=listOf("合并表头需确认"))
            val words=grid.cells.map { cell ->
                val region=grid.region(cell)
                val text=when(cell.row) { 0 -> if(mismatch && source.pageIndex>0)"其他" else "业务"; 1 -> if(cell.column==0)"编号" else "数值"; else -> "${source.pageIndex*100+cell.row*10+cell.column}" }
                TextEvidence(text,region.copy(left=region.left+.01f,right=region.right-.01f,top=region.top+.01f,bottom=region.bottom-.01f),.99f)
            }
            return TableCandidateAssembler().propose(source.id,image,grid,words)
        }
        override suspend fun revise(source: SourceDocument,grid: TableGrid,text: List<TextEvidence>)=TableCandidateAssembler().propose(source.id,image,grid,text,false)
    }
}
