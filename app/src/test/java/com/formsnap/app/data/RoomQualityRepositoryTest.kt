package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.*
import com.formsnap.app.domain.model.*
import com.formsnap.app.review.*
import com.formsnap.app.validation.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomQualityRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: FormSnapDatabase
    private lateinit var quality: RoomQualityRepository
    private lateinit var structured: RoomStructuredRepository
    private lateinit var sourceRepository: RoomSourceRepository
    private var taskId = ""
    private var sourceId = ""
    private val fields = listOf(FieldDefinition("id", "编号", FieldType.ID, FieldRules(required = true, duplicateKey = true)),
        FieldDefinition("number", "数值", FieldType.INTEGER, FieldRules(required = true, minimum = "0", maximum = "710")))

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("q.db")
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, FormSnapDatabase::class.java, "q.db").build()
        quality = RoomQualityRepository(db)
        structured = RoomStructuredRepository(db)
        sourceRepository = RoomSourceRepository(db, FakeSourceAccess())
    }
    @After fun close() { db.close(); context.deleteDatabase("q.db") }

    private suspend fun seed(values: List<List<String?>> = listOf(listOf("001", "S12")), low: Boolean = false) {
        taskId = RoomTaskRepository(db.taskDao()).createTask("质量测试").id
        sourceRepository.addSources(taskId, listOf("content://test/page"))
        sourceId = sourceRepository.observeSources(taskId).first().single().id
        structured.createSchema(taskId, fields)
        structured.replacePage(taskId, candidate(values, low))
    }
    private fun candidate(values: List<List<String?>>, low: Boolean = false) = CandidatePage(sourceId, fields.map { it.name }, values.mapIndexed { row, items ->
        CandidateRow(row, items.mapIndexed { col, value -> CandidateCell(value,
            SourceRegion(col * 0.5f, 0.1f, (col + 1) * 0.5f, 0.2f),
            if (low) RecognitionReliability.LOW else RecognitionReliability.HIGH) })
    })
    private suspend fun status() = TaskStatus.valueOf(db.taskDao().getTask(taskId)!!.status)
    private suspend fun cell() = structured.getDataset(taskId)!!.rows.single().cells[1]

    @Test fun `all clear becomes ready only after validation and missing source pages still block`() = runTest {
        seed(listOf(listOf("001", "512")))
        assertEquals(TaskStatus.PROCESSING, status())
        quality.revalidate(taskId)
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
        assertEquals("512", quality.exportableDataset(taskId).rows.single().cells[1].confirmedValue)
        sourceRepository.addSources(taskId, listOf("content://test/not-processed"))
        assertEquals(TaskStatus.REVIEW_REQUIRED, status())
        try { quality.exportableDataset(taskId); fail("Must not export an unfinished page") } catch (_: IllegalStateException) { }
    }

    @Test fun `edit closes all cell issues stores history preserves raw and survives database reopening`() = runTest {
        seed(low = true)
        val initial = quality.revalidate(taskId)
        val target = cell()
        val item = ReviewQueue.items(initial.issues).single { it.targetId == target.id }
        assertEquals(2, item.issues.size)
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, target.id, ReviewAction.EDIT, "512", item.revision, "S12"))
        assertEquals("S12", cell().rawValue)
        assertEquals("512", cell().confirmedValue)
        assertEquals(CellReviewStatus.MANUAL_CONFIRMED, cell().reviewStatus)
        val audit = db.qualityDao().decisions(taskId).single()
        assertEquals("S12", audit.rawValue)
        assertEquals("S12", audit.previousValue)
        assertEquals("512", audit.finalValue)
        db.close(); reopen()
        assertEquals("512", cell().confirmedValue)
        val pending = ReviewQueue.items(quality.revalidate(taskId).issues)
        assertFalse(pending.any { it.targetId == target.id })
        assertEquals(1, pending.size)
    }

    @Test fun `confirm and keep can explicitly accept correct outliers and required blanks`() = runTest {
        seed(listOf(listOf("001", "812")))
        quality.revalidate(taskId)
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.CONFIRM))
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
        assertEquals("812", cell().confirmedValue)
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.EDIT, ""))
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.KEEP))
        assertEquals("", cell().confirmedValue)
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
    }

    @Test fun `unreadable persists blocking state and can later be corrected`() = runTest {
        seed()
        quality.revalidate(taskId)
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.UNREADABLE))
        assertEquals(CellReviewStatus.UNREADABLE, cell().reviewStatus)
        assertNull(cell().confirmedValue)
        assertEquals(TaskStatus.REVIEW_REQUIRED, status())
        assertTrue(quality.revalidate(taskId).issues.any { it.status == IssueStatus.UNCONFIRMABLE })
        try { quality.exportableDataset(taskId); fail("Unreadable cannot export") } catch (_: IllegalStateException) { }
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.EDIT, "512"))
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
    }

    @Test fun `repeat validation preserves decisions but changed field rules reopen relevant issues`() = runTest {
        seed(listOf(listOf("001", "812")))
        quality.revalidate(taskId)
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.KEEP))
        assertTrue(ReviewQueue.items(quality.revalidate(taskId).issues).isEmpty())
        quality.updateFields(taskId, fields.map { if (it.id == "number") it.copy(rules = it.rules.copy(maximum = "600")) else it })
        assertEquals(TaskStatus.REVIEW_REQUIRED, status())
        assertTrue(ReviewQueue.items(quality.revalidate(taskId).issues).any { it.targetId == cell().id })
    }

    @Test fun `duplicate group can be retained as distinct records without automatic deletion`() = runTest {
        seed(listOf(listOf("001", "512"), listOf("001", "600")))
        val duplicate = quality.revalidate(taskId).issues.single { it.code == IssueCode.DUPLICATE_RECORD }
        assertEquals(TaskStatus.REVIEW_REQUIRED, status())
        quality.decide(taskId, ReviewDecision(IssueTarget.ROW_GROUP, duplicate.targetId, ReviewAction.DISTINCT_RECORDS))
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
        assertEquals(2, quality.exportableDataset(taskId).rows.count { !it.excluded })
        assertFalse(quality.revalidate(taskId).issues.any { it.status != IssueStatus.RESOLVED })
    }

    @Test fun `excluding a duplicate preserves evidence but removes it from active validation`() = runTest {
        seed(listOf(listOf("001", "512"), listOf("001", "600")))
        val duplicate = quality.revalidate(taskId).issues.single { it.code == IssueCode.DUPLICATE_RECORD }
        quality.decide(taskId, ReviewDecision(IssueTarget.ROW_GROUP, duplicate.targetId, ReviewAction.EXCLUDE_RECORD, duplicate.relatedRowIds[1]))
        val data = quality.exportableDataset(taskId)
        assertEquals(2, data.rows.size)
        assertEquals(1, data.rows.count { it.excluded })
        assertEquals(4, db.structuredDao().cells(taskId).size)
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
    }

    @Test fun `page issues cannot be acknowledged blindly and removal clears associated data and issue`() = runTest {
        seed(listOf(listOf("001", "512")))
        db.qualityDao().savePage(PageResultEntity(sourceId, taskId, "FAILED", IssueCode.SCHEMA_MISMATCH.name, "结构不同", 1))
        val problem = quality.revalidate(taskId).issues.single { it.target == IssueTarget.SOURCE }
        try { quality.decide(taskId, ReviewDecision(IssueTarget.SOURCE, problem.targetId, ReviewAction.CONFIRM)); fail("Cannot bypass structural failure") }
        catch (_: IllegalStateException) { }
        sourceRepository.removeSource(taskId, sourceId)
        assertEquals(TaskStatus.DRAFT, status())
        assertTrue(structured.getDataset(taskId)!!.rows.isEmpty())
        assertTrue(ReviewQueue.items(quality.revalidate(taskId).issues).isEmpty())
    }

    @Test fun `reprocessing resets prior approvals and stale review actions are rejected`() = runTest {
        seed()
        val old = ReviewQueue.items(quality.revalidate(taskId).issues).single()
        quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.KEEP))
        structured.replacePage(taskId, candidate(listOf(listOf("001", "S12"))), discardHumanWork = true)
        assertEquals(1, ReviewQueue.items(quality.revalidate(taskId).issues).size)
        structured.replacePage(taskId, candidate(listOf(listOf("001", "bad"))))
        try { quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.CONFIRM, expectedRevision = old.revision)); fail("Expected stale-review failure") }
        catch (_: StaleReviewException) { }
        assertNull(cell().confirmedValue)
    }

    @Test fun `failed audit write rolls back manual values issues and task state`() = runTest {
        seed()
        val before = quality.revalidate(taskId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_audit BEFORE INSERT ON review_decisions BEGIN SELECT RAISE(ABORT, 'test'); END")
        try { quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cell().id, ReviewAction.EDIT, "512")); fail("Expected audit failure") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(before, quality.revalidate(taskId))
        assertTrue(db.qualityDao().decisions(taskId).isEmpty())
    }

    @Test fun `source access loss blocks export even when all cells were confirmed`() = runTest {
        seed(listOf(listOf("001", "512")))
        quality.revalidate(taskId)
        db.sourceDao().updateStatus(sourceId, SourceStatus.UNAVAILABLE.name)
        try { quality.exportableDataset(taskId); fail("Lost provenance must block standard export") } catch (_: IllegalStateException) { }
        assertEquals(TaskStatus.REVIEW_REQUIRED, status())
        db.sourceDao().updateStatus(sourceId, SourceStatus.AVAILABLE.name)
        quality.revalidate(taskId)
        assertEquals(TaskStatus.READY_TO_EXPORT, status())
        db.sourceDao().updateStatus(sourceId, SourceStatus.UNAVAILABLE.name)
        assertTrue(quality.revalidate(taskId).issues.any { it.code == IssueCode.SOURCE_UNAVAILABLE && it.status == IssueStatus.OPEN })
    }
}
