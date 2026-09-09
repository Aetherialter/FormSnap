package com.formsnap.app.processing

import androidx.room.withTransaction
import com.formsnap.app.data.RoomQualityRepository
import com.formsnap.app.data.RoomStructuredRepository
import com.formsnap.app.data.RoomStructureRepository
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.data.local.PageResultEntity
import com.formsnap.app.data.local.toDomain
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.HumanWorkExistsException
import com.formsnap.app.domain.repository.SchemaMismatchException
import com.formsnap.app.validation.IssueCode
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProcessingRequest(val taskId: String, val sourceId: String? = null, val discardHumanWork: Boolean = false)
data class ProcessingSummary(val successfulPages: Int, val failedPages: Int, val structureReviewPages: Int=0)

/** One application instance serializes image-heavy jobs; committed page results survive restarts. */
class ProcessingRunner(
    private val database: FormSnapDatabase,
    private val recognizer: PageRecognizer,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val imageWork = Mutex()

    suspend fun run(request: ProcessingRequest, progress: suspend (Int, Int) -> Unit = { _, _ -> }): ProcessingSummary = imageWork.withLock {
        val taskId = request.taskId
        val quality = RoomQualityRepository(database)
        val structured = RoomStructuredRepository(database)
        val sources = database.withTransaction {
            checkNotNull(database.taskDao().getTask(taskId))
            val all = database.sourceDao().getSources(taskId)
            require(all.isNotEmpty()) { "Add source pages before processing" }
            val completed = database.qualityDao().pages(taskId).filter { it.state == "SUCCEEDED" }.map { it.sourceId }.toSet()
            val selected = if (request.sourceId != null) all.filter { it.id == request.sourceId }.also { require(it.size == 1) }
                else all.filter { it.id !in completed }
            for (source in selected) database.qualityDao().savePage(PageResultEntity(source.id, taskId, "RUNNING", null, null, clock.millis()))
            if (selected.isNotEmpty()) database.taskDao().updateSourceCollection(taskId, TaskStatus.PROCESSING.name, clock.millis())
            selected
        }
        var succeeded = 0
        var failed = 0
        var review = 0
        try {
            progress(0, sources.size)
            for ((index, source) in sources.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    if (source.status != SourceStatus.AVAILABLE.name) throw PageRecognitionException(IssueCode.SOURCE_UNAVAILABLE, "来源当前无法访问，请恢复文件或重新添加。")
                    if(recognizer is StructureRecognizer && request.sourceId==null) {
                        val draft=database.structureDao().get(taskId,source.id)
                        if(draft!=null && !draft.confirmed) {
                            val proposal=com.formsnap.app.data.StructureCodec.decode(draft.payload)
                            if(proposal.grid.outcome==StructureOutcome.STRUCTURE_REVIEW_REQUIRED) {
                                // Continue must not overwrite the user's saved separators/header edits.
                                // An explicit page reprocess is the separate way to obtain new evidence.
                                database.qualityDao().savePage(PageResultEntity(source.id,taskId,"STRUCTURE_REVIEW_REQUIRED",
                                    IssueCode.STRUCTURE_REVIEW_REQUIRED.name,"已保存结构草稿，请先确认表头与分割。",clock.millis()))
                                throw StructureReviewException(proposal)
                            }
                        }
                    }
                    val page = if(recognizer is StructureRecognizer) RoomStructureRepository(database,recognizer).process(source.toDomain(),request.discardHumanWork)
                        else recognizer.recognize(source.toDomain())
                    check(page.sourceDocumentId == source.id) { "Recognizer returned a different source" }
                    database.withTransaction {
                        if (structured.getDataset(taskId) == null) structured.createSchema(taskId,
                            page.headers.mapIndexed { i,name -> FieldDefinition(UUID.randomUUID().toString(), name, headerPath=page.headerPaths[i]) }, configurationConfirmed = false)
                        structured.replacePage(taskId, page, request.discardHumanWork)
                        if(recognizer is StructureRecognizer) database.structureDao().get(taskId,source.id)?.let {
                            database.structureDao().save(it.copy(confirmed=true,revision=it.revision+1,discardHumanWork=false))
                        }
                    }
                    succeeded++
                } catch (structure: StructureReviewException) {
                    review++
                    // Pause before later pages establish a competing schema. Resume after confirmation.
                    for(rest in sources.drop(index+1)) database.qualityDao().savePage(PageResultEntity(rest.id,taskId,"PENDING",null,"等待首张结构确认后继续整理。",clock.millis()))
                    break
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    failed++
                    failPage(taskId, source.id, IssueCode.UNREADABLE, "此页读取超时，请检查来源后重试。")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PageRecognitionException) {
                    failed++
                    failPage(taskId, source.id, failure.code, failure.explanation)
                } catch (_: SchemaMismatchException) {
                    failed++
                    failPage(taskId, source.id, IssueCode.SCHEMA_MISMATCH, "此页表头与当前任务不同，已停止合并。请核对材料，必要时移到独立任务。")
                } catch (_: HumanWorkExistsException) {
                    failed++
                    failPage(taskId, source.id, IssueCode.STRUCTURE_WARNING, "此页已有人工处理结果。重新整理前需要明确确认替换这些结果。")
                } catch (_: Exception) {
                    failed++
                    failPage(taskId, source.id, IssueCode.UNREADABLE, "此页未能完成整理，请检查原图是否完整可读后重试。")
                }
                progress(index + 1, sources.size)
            }
            quality.revalidate(taskId)
            ProcessingSummary(succeeded, failed, review)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { recoverInterrupted(taskId) }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) { recoverInterrupted(taskId) }
            throw failure
        }
    }

    private suspend fun failPage(taskId: String, sourceId: String, code: IssueCode, message: String) {
        val state=if(code==IssueCode.UNREADABLE || code==IssueCode.SOURCE_UNAVAILABLE)"SOURCE_UNUSABLE" else "FAILED"
        database.qualityDao().savePage(PageResultEntity(sourceId, taskId, state, code.name, message, clock.millis()))
    }

    private suspend fun recoverInterrupted(taskId: String) = database.withTransaction {
        for (page in database.qualityDao().pages(taskId).filter { it.state == "RUNNING" }) {
            failPage(taskId, page.sourceId, IssueCode.STRUCTURE_WARNING, "整理尚未完成，可以重新执行。已保存的来源与结果仍然保留。")
        }
        RoomQualityRepository(database).revalidate(taskId)
    }
}
