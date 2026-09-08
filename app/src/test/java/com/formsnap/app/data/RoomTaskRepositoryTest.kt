package com.formsnap.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomTaskRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormSnapDatabase
    private lateinit var repository: RoomTaskRepository
    private val instant = Instant.parse("2026-09-08T00:00:00.123Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, FormSnapDatabase::class.java, DATABASE_NAME).build()
        repository = RoomTaskRepository(database.taskDao(), clock)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `creation persists trimmed draft with consistent timestamps`() = runTest {
        val task = repository.createTask("  资产登记  ")
        assertEquals("资产登记", task.name)
        assertEquals(TaskStatus.DRAFT, task.status)
        assertEquals(instant, task.createdAt)
        assertEquals(instant, task.updatedAt)
        assertEquals(task, repository.observeTask(task.id).first())
    }

    @Test
    fun `task survives closing and reopening a file backed database`() = runTest {
        val task = repository.createTask("设备巡检")
        database.close()
        openDatabase()
        assertEquals(task, repository.observeTask(task.id).first())
        assertEquals(listOf(task), repository.observeTasks().first())
    }

    @Test
    fun `same names create independent tasks and missing ID stays absent`() = runTest {
        val first = repository.createTask("登记表")
        val second = repository.createTask("登记表")
        assertNotEquals(first.id, second.id)
        assertEquals(2, repository.observeTasks().first().size)
        assertNull(repository.observeTask("missing").first())
    }

    @Test
    fun `invalid names never write a row`() = runTest {
        for (name in listOf("", "  \n ", "x".repeat(DigitizationTask.MAX_NAME_LENGTH + 1))) {
            val result = runCatching { repository.createTask(name) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(repository.observeTasks().first().isEmpty())
        repository.createTask("x".repeat(DigitizationTask.MAX_NAME_LENGTH))
        assertEquals(1, repository.observeTasks().first().size)
    }

    @Test
    fun `ID collision fails without overwriting persisted work`() = runTest {
        val fixedIdRepository = RoomTaskRepository(database.taskDao(), clock) { "fixed-id" }
        val original = fixedIdRepository.createTask("原任务")
        assertTrue(runCatching { fixedIdRepository.createTask("不应覆盖") }.isFailure)
        assertEquals(listOf(original), repository.observeTasks().first())
    }

    @Test
    fun `recent tasks are ordered by time descending`() = runTest {
        val earlier = repository.createTask("较早任务")
        val laterRepository = RoomTaskRepository(database.taskDao(), Clock.offset(clock, java.time.Duration.ofMinutes(1)))
        val later = laterRepository.createTask("较新任务")
        assertEquals(listOf(later.id, earlier.id), repository.observeTasks().first().map { it.id })
    }

    companion object {
        private const val DATABASE_NAME = "repository-test.db"
    }
}
