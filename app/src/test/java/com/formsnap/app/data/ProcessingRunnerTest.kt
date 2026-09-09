package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.*
import com.formsnap.app.domain.model.*
import com.formsnap.app.processing.*
import com.formsnap.app.validation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
class ProcessingRunnerTest {
    private lateinit var context: Context
    private lateinit var db: FormSnapDatabase
    private var taskId = ""
    private lateinit var sources: List<SourceDocument>

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("p.db")
        db = Room.databaseBuilder(context, FormSnapDatabase::class.java, "p.db").build()
    }
    @After fun close() { db.close(); context.deleteDatabase("p.db") }
    private suspend fun seed() {
        taskId = RoomTaskRepository(db.taskDao()).createTask("批量").id
        val repository = RoomSourceRepository(db, FakeSourceAccess())
        repository.addSources(taskId, listOf("content://test/a", "content://test/b"))
        sources = repository.observeSources(taskId).first()
    }
    private fun candidate(source: SourceDocument, header: String = "资产编号") = CandidatePage(source.id, listOf(header, "设备"), listOf(CandidateRow(1,
        listOf(CandidateCell("编号${source.pageIndex}", SourceRegion(0.1f, 0.2f, 0.4f, 0.3f), RecognitionReliability.HIGH),
            CandidateCell("设备${source.pageIndex}", SourceRegion(0.5f, 0.2f, 0.9f, 0.3f), RecognitionReliability.HIGH)))))

    @Test fun `batch persists pages and requires human schema configuration before export`() = runTest {
        seed()
        val progress = mutableListOf<Pair<Int, Int>>()
        val runner = ProcessingRunner(db, PageRecognizer { candidate(it) })
        assertEquals(ProcessingSummary(2, 0), runner.run(ProcessingRequest(taskId)) { a, b -> progress += a to b })
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        val quality = RoomQualityRepository(db)
        assertTrue(quality.revalidate(taskId).issues.any { it.target == IssueTarget.SCHEMA && it.status == IssueStatus.OPEN })
        val data = RoomStructuredRepository(db).getDataset(taskId)!!
        assertEquals(sources.map { it.id }, data.rows.map { it.sourceDocumentId })
        quality.updateFields(taskId, data.schema.fields)
        assertEquals(2, quality.exportableDataset(taskId).rows.size)
    }

    @Test fun `schema mismatch is persisted and cannot silently join compatible page rows`() = runTest {
        seed()
        val runner = ProcessingRunner(db, PageRecognizer { candidate(it, if (it.pageIndex == 0) "资产编号" else "登记人") })
        assertEquals(ProcessingSummary(1, 1), runner.run(ProcessingRequest(taskId)))
        assertEquals(1, RoomStructuredRepository(db).getDataset(taskId)!!.rows.size)
        assertEquals(IssueCode.SCHEMA_MISMATCH.name, db.qualityDao().pages(taskId).single { it.sourceId == sources[1].id }.problemCode)
        assertEquals(TaskStatus.REVIEW_REQUIRED.name, db.taskDao().getTask(taskId)!!.status)
    }

    @Test fun `unreadable page is visible and successful siblings remain available`() = runTest {
        seed()
        val runner = ProcessingRunner(db, PageRecognizer {
            if (it.pageIndex == 0) throw PageRecognitionException(IssueCode.UNREADABLE, "无法辨认") else candidate(it)
        })
        assertEquals(ProcessingSummary(1, 1), runner.run(ProcessingRequest(taskId)))
        val result = RoomQualityRepository(db).revalidate(taskId)
        assertEquals(sources[1].id, result.dataset!!.rows.single().sourceDocumentId)
        assertTrue(result.issues.any { it.targetId == sources[0].id && it.code == IssueCode.UNREADABLE })
    }

    @Test fun `cancelled work retains definition and can restart without duplicating completed pages`() = runTest {
        seed()
        val enteredSecond = CompletableDeferred<Unit>()
        val runner = ProcessingRunner(db, PageRecognizer {
            if (it.pageIndex == 1) { enteredSecond.complete(Unit); awaitCancellation() }
            candidate(it)
        })
        val job = launch { runner.run(ProcessingRequest(taskId)) }
        enteredSecond.await()
        job.cancelAndJoin()
        assertEquals(1, RoomStructuredRepository(db).getDataset(taskId)!!.rows.size)
        assertEquals("FAILED", db.qualityDao().pages(taskId).single { it.sourceId == sources[1].id }.state)
        val recognized = mutableListOf<String>()
        val resumed = ProcessingRunner(db, PageRecognizer { recognized += it.id; candidate(it) })
        resumed.run(ProcessingRequest(taskId))
        assertEquals(listOf(sources[1].id), recognized)
        assertEquals(2, RoomStructuredRepository(db).getDataset(taskId)!!.rows.size)
    }

    @Test fun `persisted running page from killed process can be retried after database reopen`() = runTest {
        seed()
        db.qualityDao().savePage(PageResultEntity(sources[0].id, taskId, "RUNNING", null, null, 1))
        db.taskDao().updateSourceCollection(taskId, TaskStatus.PROCESSING.name, 1)
        db.close()
        db = Room.databaseBuilder(context, FormSnapDatabase::class.java, "p.db").build()
        val runner = ProcessingRunner(db, PageRecognizer { candidate(it) })
        assertEquals(ProcessingSummary(2, 0), runner.run(ProcessingRequest(taskId)))
        assertTrue(db.qualityDao().pages(taskId).all { it.state == "SUCCEEDED" })
    }

    @Test fun `source edits are rejected while processing and a selected page can be rerun`() = runTest {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = ProcessingRunner(db, PageRecognizer { entered.complete(Unit); release.await(); candidate(it) })
        val job = launch { runner.run(ProcessingRequest(taskId)) }
        entered.await()
        try { RoomSourceRepository(db, FakeSourceAccess()).removeSource(taskId, sources[0].id); fail("Must protect active source") }
        catch (_: IllegalStateException) { }
        release.complete(Unit)
        job.join()
        val before = RoomStructuredRepository(db).getDataset(taskId)!!.rows
        runner.run(ProcessingRequest(taskId, sourceId = sources[0].id))
        assertEquals(before, RoomStructuredRepository(db).getDataset(taskId)!!.rows)
    }
}
