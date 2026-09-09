package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.*
import com.formsnap.app.export.*
import com.formsnap.app.review.*
import com.formsnap.app.validation.IssueTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidXlsxExporterTest {
    private lateinit var db: FormSnapDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var quality: RoomQualityRepository
    private lateinit var exporter: AndroidXlsxExporter
    private lateinit var provider: TestExportProvider
    private val access = FakeSourceAccess()
    private var taskId = ""
    private var cellId = ""

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, FormSnapDatabase::class.java).build()
        sources = RoomSourceRepository(db, access)
        quality = RoomQualityRepository(db)
        exporter = AndroidXlsxExporter(db, context.contentResolver, sources)
        provider = TestExportProvider.register(context)
        taskId = RoomTaskRepository(db.taskDao()).createTask("导出测试").id
        sources.addSources(taskId, listOf("content://test/page"))
        val source = sources.observeSources(taskId).first().single()
        val structured = RoomStructuredRepository(db)
        structured.createSchema(taskId, listOf(FieldDefinition("id", "资产编号", FieldType.ID)))
        structured.replacePage(taskId, CandidatePage(source.id, listOf("资产编号"), listOf(CandidateRow(0,
            listOf(CandidateCell("001", SourceRegion(0f, 0f, 1f, 1f), RecognitionReliability.HIGH))))))
        cellId = quality.revalidate(taskId).dataset!!.rows.single().cells.single().id
    }
    @After fun close() { db.close() }

    @Test fun `destination failure preserves ready state without claiming exported`() = runTest {
        provider.fail = true
        try { exporter.export(taskId, TestExportProvider.URI); fail("Must propagate output failure") }
        catch (_: java.io.FileNotFoundException) { }
        assertEquals(TaskStatus.READY_TO_EXPORT.name, db.taskDao().getTask(taskId)!!.status)
    }

    @Test fun `edit while saving returns data changed and does not mark new data exported`() = runTest {
        provider.beforeOpen = { runBlocking { quality.decide(taskId, ReviewDecision(IssueTarget.CELL, cellId, ReviewAction.EDIT, "002")) } }
        assertEquals(ExportOutcome.DATA_CHANGED, exporter.export(taskId, TestExportProvider.URI))
        assertEquals(TaskStatus.READY_TO_EXPORT.name, db.taskDao().getTask(taskId)!!.status)
        assertEquals("002", quality.exportableDataset(taskId).rows.single().cells.single().confirmedValue)
    }

    @Test fun `lost source is discovered at export and blocking state persists`() = runTest {
        access.inaccessible += "content://test/page"
        var opened = false
        provider.beforeOpen = { opened = true }
        try { exporter.export(taskId, TestExportProvider.URI); fail("Lost source must block output") }
        catch (_: IllegalStateException) { }
        assertFalse(opened)
        assertEquals(TaskStatus.REVIEW_REQUIRED.name, db.taskDao().getTask(taskId)!!.status)
    }
}
