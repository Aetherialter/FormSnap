package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.SchemaMismatchException
import java.util.UUID
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
class RoomStructuredRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormSnapDatabase
    private lateinit var repository: RoomStructuredRepository
    // Robolectric already isolates each test directory. Keep the file short for Windows SQLite WAL paths.
    private val name = "s.db"

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, FormSnapDatabase::class.java, name).build()
        repository = RoomStructuredRepository(database)
    }

    @After fun close() { database.close(); context.deleteDatabase(name) }

    private suspend fun task(headers: List<String> = listOf("资产编号", "设备名称", "状态", "备注")): Pair<String, List<SourceDocument>> {
        val task = RoomTaskRepository(database.taskDao()).createTask("测试")
        val sources = RoomSourceRepository(database, FakeSourceAccess())
        sources.addSources(task.id, listOf("content://test/images/a", "content://test/images/b"))
        repository.createSchema(task.id, headers.map { FieldDefinition(UUID.randomUUID().toString(), it) })
        return task.id to sources.observeSources(task.id).first()
    }

    private suspend fun page(taskId: String, source: SourceDocument, value: String = " 001 ", rowIndex: Int = 0): CandidatePage {
        val fields = repository.getDataset(taskId)!!.schema.fields
        return CandidatePage(source.id, fields.map { it.name }, listOf(CandidateRow(rowIndex,
            fields.mapIndexed { index, _ -> CandidateCell(if (index == 0) value else "内容$index",
                SourceRegion(index.toFloat() / fields.size, 0.2f, (index + 1f) / fields.size, 0.3f), RecognitionReliability.HIGH) },
        )))
    }

    @Test fun `two unrelated dynamic schemas preserve all field associations and unvalidated semantics`() = runTest {
        for (headers in listOf(listOf("姓名", "学号", "等级", "成绩", "课程", "签字"), listOf("资产编号", "设备名称", "状态", "备注"))) {
            val (id, sources) = task(headers)
            repository.replacePage(id, page(id, sources[0]))
            val result = repository.getDataset(id)!!
            assertEquals(headers, result.schema.fields.map { it.name })
            assertEquals(result.schema.fields.map { it.id }, result.rows.single().cells.map { it.fieldId })
            result.rows.single().cells.forEach {
                assertNull(it.normalizedValue)
                assertNull(it.confirmedValue)
                assertEquals(CellReviewStatus.UNVALIDATED, it.reviewStatus)
                assertEquals(sources[0].id, it.source!!.documentId)
            }
            assertEquals(TaskStatus.PROCESSING.name, database.taskDao().getTask(id)!!.status)
        }
    }

    @Test fun `multiple pages are ordered by source and original row independent of insertion order`() = runTest {
        val (id, sources) = task()
        repository.replacePage(id, page(id, sources[1], "B", 3))
        repository.replacePage(id, page(id, sources[0], "A", 7))
        assertEquals(listOf("A", "B"), repository.getDataset(id)!!.rows.map { it.cells[0].rawValue })
        assertEquals(listOf(7, 3), repository.getDataset(id)!!.rows.map { it.originalRowIndex })
    }

    @Test fun `file database reopen preserves complete dataset including regions`() = runTest {
        val (id, sources) = task()
        repository.replacePage(id, page(id, sources[0]))
        val before = repository.getDataset(id)
        database.close()
        database = Room.databaseBuilder(context, FormSnapDatabase::class.java, name).build()
        repository = RoomStructuredRepository(database)
        assertEquals(before, repository.observeDataset(id).first())
    }

    @Test fun `configured generic field rules survive repository reads`() = runTest {
        val task = RoomTaskRepository(database.taskDao()).createTask("规则")
        val fields = listOf(
            FieldDefinition("number", "金额", FieldType.DECIMAL, FieldRules(true, "[0-9]+", "0", "1000", true, true)),
            FieldDefinition("signature", "签署", FieldType.SIGNATURE_PRESENCE, FieldRules(required = true)),
        )
        val schema = repository.createSchema(task.id, fields)
        assertEquals(schema, repository.getDataset(task.id)!!.schema)
    }

    @Test fun `field display reordering preserves original source columns and cell ids on reprocessing`() = runTest {
        val (id, sources) = task()
        val input = page(id, sources[0])
        repository.replacePage(id, input)
        val before = repository.getDataset(id)!!
        repository.reorderFields(id, before.schema.fields.map { it.id }.reversed())
        repository.replacePage(id, input)
        val after = repository.getDataset(id)!!
        assertEquals(before.rows.single().cells.reversed(), after.rows.single().cells)
        assertEquals(before.rows.single().id, after.rows.single().id)
    }

    @Test fun `schema mismatch rejects entire page without deleting previous candidates`() = runTest {
        val (id, sources) = task()
        val input = page(id, sources[0])
        repository.replacePage(id, input)
        val before = repository.getDataset(id)
        try {
            repository.replacePage(id, input.copy(headers = input.headers.map { "$it 不同" }))
            fail("Expected mismatch")
        } catch (_: SchemaMismatchException) { }
        assertEquals(before, repository.getDataset(id))
    }

    @Test fun `foreign task source is rejected and direct foreign keys reject cross schema cells`() = runTest {
        val (a, sourcesA) = task()
        val (b, sourcesB) = task()
        try { repository.replacePage(a, page(a, sourcesB[0])); fail("Expected source rejection") }
        catch (_: IllegalArgumentException) { }
        repository.replacePage(a, page(a, sourcesA[0]))
        val cell = database.structuredDao().cells(a).first()
        val foreignField = repository.getDataset(b)!!.schema.fields.first().id
        try { database.structuredDao().insertCells(listOf(cell.copy(id = "foreign-cell", fieldId = foreignField))); fail("Expected FK rejection") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(repository.getDataset(b)!!.rows.isEmpty())
    }

    @Test fun `failed replacement rolls back original rows and task state`() = runTest {
        val (id, sources) = task()
        repository.replacePage(id, page(id, sources[0], "old"))
        val before = repository.getDataset(id)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_cells BEFORE INSERT ON cells BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { repository.replacePage(id, page(id, sources[0], "new")); fail("Expected insertion failure") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(before, repository.getDataset(id))
    }

    @Test fun `source deletion cascades only its own structured rows`() = runTest {
        val (id, sources) = task()
        val (other, otherSources) = task()
        for (source in sources) repository.replacePage(id, page(id, source))
        repository.replacePage(other, page(other, otherSources[0]))
        database.sourceDao().delete(id, sources[0].id)
        assertEquals(sources[1].id, repository.getDataset(id)!!.rows.single().sourceDocumentId)
        assertEquals(1, repository.getDataset(other)!!.rows.size)
        assertEquals(4, database.structuredDao().cells(id).size)
    }

    @Test fun `human values require explicit discard and retain raw until authorized reprocessing`() = runTest {
        val (id, sources) = task()
        repository.replacePage(id, page(id, sources[0], "S12"))
        val cell = repository.getDataset(id)!!.rows.single().cells.first()
        database.openHelper.writableDatabase.execSQL("UPDATE cells SET confirmedValue = '512', reviewStatus = 'MANUAL_CONFIRMED' WHERE id = ?", arrayOf(cell.id))
        try { repository.replacePage(id, page(id, sources[0], "new")); fail("Expected human-work guard") }
        catch (_: com.formsnap.app.domain.repository.HumanWorkExistsException) { }
        val saved = repository.getDataset(id)!!.rows.single().cells.first()
        assertEquals("S12", saved.rawValue)
        assertEquals("512", saved.confirmedValue)
        repository.replacePage(id, page(id, sources[0], "new"), discardHumanWork = true)
        val reset = repository.getDataset(id)!!.rows.single().cells.first()
        assertEquals(cell.id, reset.id)
        assertNull(reset.confirmedValue)
        assertEquals("new", reset.rawValue)
    }

    @Test fun `duplicate row field uniqueness and invalid reordering cannot corrupt saved data`() = runTest {
        val (id, sources) = task()
        repository.replacePage(id, page(id, sources[0]))
        val before = repository.getDataset(id)!!
        val cell = database.structuredDao().cells(id).first()
        try { database.structuredDao().insertCells(listOf(cell.copy(id = "duplicate"))); fail("Expected uniqueness failure") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        try { repository.reorderFields(id, List(4) { before.schema.fields.first().id }); fail("Expected invalid permutation") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.getDataset(id))
    }
}
