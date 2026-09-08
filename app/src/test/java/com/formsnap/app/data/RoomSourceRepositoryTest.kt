package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.data.local.SourceEntity
import com.formsnap.app.domain.model.SourceStatus
import com.formsnap.app.domain.model.TaskStatus
import com.formsnap.app.domain.repository.SourceImportFailure
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
class RoomSourceRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormSnapDatabase
    private lateinit var tasks: RoomTaskRepository
    private lateinit var repository: RoomSourceRepository
    private val access = FakeSourceAccess()
    private val clock = Clock.fixed(Instant.parse("2026-09-08T02:00:00.123Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(NAME)
        open()
    }
    private fun open() {
        database = Room.databaseBuilder(context, FormSnapDatabase::class.java, NAME)
            .addMigrations(FormSnapDatabase.MIGRATION_1_2).build()
        tasks = RoomTaskRepository(database.taskDao(), clock)
        repository = RoomSourceRepository(database, access, clock)
    }
    @After fun tearDown() { database.close(); context.deleteDatabase(NAME) }

    @Test fun `batch preserves selection order and removes repeated URI callbacks`() = runTest {
        val task = tasks.createTask("巡检")
        val result = repository.addSources(task.id, listOf(B, A, B))
        assertEquals(2, result.added)
        assertEquals(1, result.duplicates)
        val again = repository.addSources(task.id, listOf(A, B))
        assertEquals(0, again.added)
        assertEquals(2, again.duplicates)
        val pages = repository.observeSources(task.id).first()
        assertEquals(listOf(B, A), pages.map { it.sourceUri })
        assertEquals(listOf(0, 1), pages.map { it.pageIndex })
        assertTrue(pages.all { it.taskId == task.id && it.createdAt == clock.instant() })
        assertEquals(TaskStatus.CAPTURING, tasks.observeTask(task.id).first()!!.status)
    }

    @Test fun `same URI in two tasks keeps permission until last reference removed`() = runTest {
        val first = tasks.createTask("一")
        val second = tasks.createTask("二")
        repository.addSources(first.id, listOf(A))
        repository.addSources(second.id, listOf(A))
        val firstPage = repository.observeSources(first.id).first().single()
        val secondPage = repository.observeSources(second.id).first().single()
        assertNotEquals(firstPage.id, secondPage.id)
        assertFalse(repository.removeSource(first.id, secondPage.id).removed)
        repository.removeSource(first.id, firstPage.id)
        assertTrue(A in access.grants)
        assertEquals(listOf(secondPage), repository.observeSources(second.id).first())
        assertEquals(TaskStatus.DRAFT, tasks.observeTask(first.id).first()!!.status)
        repository.removeSource(second.id, secondPage.id)
        assertFalse(A in access.grants)
        assertEquals(listOf(A), access.released)
    }

    @Test fun `delete compacts page numbers and next import appends deterministically`() = runTest {
        val task = tasks.createTask("多页")
        repository.addSources(task.id, listOf(A, B, C))
        val before = repository.observeSources(task.id).first()
        repository.removeSource(task.id, before[1].id)
        repository.addSources(task.id, listOf(B))
        val after = repository.observeSources(task.id).first()
        assertEquals(listOf(A, C, B), after.map { it.sourceUri })
        assertEquals(listOf(0, 1, 2), after.map { it.pageIndex })
        assertEquals(before[2].id, after[1].id)
    }

    @Test fun `file backed sources survive reopening with all metadata intact`() = runTest {
        val task = tasks.createTask("恢复")
        repository.addSources(task.id, listOf(A, B))
        val before = repository.observeSources(task.id).first()
        database.close()
        open()
        assertEquals(before, repository.observeSources(task.id).first())
        repository.refreshAvailability(task.id)
        assertEquals(before, repository.observeSources(task.id).first())
    }

    @Test fun `access loss and recovery change status without losing identity or order`() = runTest {
        val task = tasks.createTask("失效")
        repository.addSources(task.id, listOf(A, B))
        val before = repository.observeSources(task.id).first()
        access.inaccessible += A
        access.grants -= B
        repository.refreshAvailability(task.id)
        val unavailable = repository.observeSources(task.id).first()
        assertEquals(before.map { it.id }, unavailable.map { it.id })
        assertTrue(unavailable.all { it.status == SourceStatus.UNAVAILABLE })
        database.close()
        open()
        assertEquals(unavailable, repository.observeSources(task.id).first())
        access.inaccessible.clear()
        access.grants += B
        repository.refreshAvailability(task.id)
        assertTrue(repository.observeSources(task.id).first().all { it.status == SourceStatus.AVAILABLE })
    }

    @Test fun `permission and access failures reject only affected inputs and clean unused grants`() = runTest {
        val task = tasks.createTask("部分成功")
        access.denied += B
        access.inaccessible += C
        val result = repository.addSources(task.id, listOf(A, B, C))
        assertEquals(1, result.added)
        assertEquals(listOf(SourceImportFailure.PERMISSION_NOT_RETAINED, SourceImportFailure.UNREADABLE), result.rejected.map { it.reason })
        assertEquals(setOf(A), access.grants)
        assertEquals(listOf(A), repository.observeSources(task.id).first().map { it.sourceUri })
    }

    @Test fun `failed database insert rolls back whole batch and task status and grants`() = runTest {
        val task = tasks.createTask("失败")
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON source_documents BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { repository.addSources(task.id, listOf(A, B)) }.isFailure)
        assertTrue(repository.observeSources(task.id).first().isEmpty())
        assertEquals(task, tasks.observeTask(task.id).first())
        assertTrue(access.grants.isEmpty())
    }

    @Test fun `delete failure retains rows and read permission`() = runTest {
        val task = tasks.createTask("删除失败")
        repository.addSources(task.id, listOf(A))
        val before = repository.observeSources(task.id).first()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON source_documents BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertTrue(runCatching { repository.removeSource(task.id, before.single().id) }.isFailure)
        assertEquals(before, repository.observeSources(task.id).first())
        assertEquals(setOf(A), access.grants)
    }

    @Test fun `missing parent is rejected before taking permissions and FK enforces association`() = runTest {
        assertTrue(runCatching { repository.addSources("missing", listOf(A)) }.isFailure)
        assertTrue(access.retained.isEmpty())
        assertTrue(runCatching {
            database.sourceDao().insert(listOf(SourceEntity("bad", "missing", A, 0, "AVAILABLE", 1, null)))
        }.isFailure)
    }

    @Test fun `empty picker result leaves storage and permissions untouched`() = runTest {
        val task = tasks.createTask("取消")
        assertEquals(0, repository.addSources(task.id, emptyList()).added)
        assertTrue(access.retained.isEmpty())
        assertEquals(task, tasks.observeTask(task.id).first())
        assertTrue(repository.observeSources(task.id).first().isEmpty())
    }

    @Test fun `concurrent repeated callbacks remain idempotent`() = runTest {
        val task = tasks.createTask("回调")
        val first = async { repository.addSources(task.id, listOf(A, B)) }
        val second = async { repository.addSources(task.id, listOf(B, C)) }
        assertEquals(3, first.await().added + second.await().added)
        val pages = repository.observeSources(task.id).first()
        assertEquals(3, pages.size)
        assertEquals(setOf(A, B, C), pages.map { it.sourceUri }.toSet())
        assertEquals(listOf(0, 1, 2), pages.map { it.pageIndex })
    }

    @Test fun `failed grant cleanup is retried without restoring removed rows`() = runTest {
        val task = tasks.createTask("清理")
        repository.addSources(task.id, listOf(A))
        val id = repository.observeSources(task.id).first().single().id
        access.failRelease = true
        assertTrue(repository.removeSource(task.id, id).permissionCleanupPending)
        assertTrue(repository.observeSources(task.id).first().isEmpty())
        access.failRelease = false
        repository.refreshAvailability(task.id)
        assertTrue(access.grants.isEmpty())
    }

    @Test fun `cancelled import releases provisional grants without partial rows`() = runTest {
        val task = tasks.createTask("取消导入")
        val reachedSecondImage = CompletableDeferred<Unit>()
        access.beforeInspect = { uri ->
            if (uri == B) {
                reachedSecondImage.complete(Unit)
                awaitCancellation()
            }
        }
        val import = launch { repository.addSources(task.id, listOf(A, B)) }
        reachedSecondImage.await()
        import.cancelAndJoin()
        assertTrue(repository.observeSources(task.id).first().isEmpty())
        assertTrue(access.grants.isEmpty())
        assertEquals(task, tasks.observeTask(task.id).first())
    }

    @Test fun `grant eviction does not silently accept non-durable pages or discard existing ones`() = runTest {
        val task = tasks.createTask("授权上限")
        repository.addSources(task.id, listOf(A))
        val original = repository.observeSources(task.id).first().single()
        access.grantCapacity = 1
        val result = repository.addSources(task.id, listOf(B, C))
        assertEquals(1, result.added)
        assertEquals(listOf(B), result.rejected.map { it.uri })
        assertEquals(SourceImportFailure.PERMISSION_NOT_RETAINED, result.rejected.single().reason)
        val pages = repository.observeSources(task.id).first()
        assertEquals(listOf(A, C), pages.map { it.sourceUri })
        assertEquals(original.id, pages[0].id)
        assertEquals(SourceStatus.UNAVAILABLE, pages[0].status)
        assertEquals(SourceStatus.AVAILABLE, pages[1].status)
    }

    companion object {
        private const val NAME = "sources-test.db"
        const val A = "content://formsnap.test/a"
        const val B = "content://formsnap.test/b"
        const val C = "content://formsnap.test/c"
    }
}
