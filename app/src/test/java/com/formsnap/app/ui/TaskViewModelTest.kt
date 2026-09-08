package com.formsnap.app.ui

import androidx.lifecycle.SavedStateHandle
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus
import com.formsnap.app.domain.repository.TaskRepository
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `blank name is rejected without calling repository`() = runTest(dispatcher) {
        val repository = TestRepository()
        val model = TaskViewModel(repository, SavedStateHandle())
        model.changeName("   ")
        model.createTask()
        runCurrent()
        assertEquals(0, repository.createCalls)
        assertNull(model.createdTaskId.value)
    }

    @Test
    fun `long name remains intact and is not submitted`() = runTest(dispatcher) {
        val repository = TestRepository()
        val model = TaskViewModel(repository, SavedStateHandle())
        val longName = "x".repeat(DigitizationTask.MAX_NAME_LENGTH) + "设备"
        model.changeName(longName)
        model.createTask()
        runCurrent()
        assertEquals(longName, model.taskName.value)
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun `save failure keeps input and allows retry`() = runTest(dispatcher) {
        val repository = TestRepository().apply { failCreate = true }
        val model = TaskViewModel(repository, SavedStateHandle())
        model.changeName("巡检表")
        model.createTask()
        runCurrent()
        assertEquals(CreationState.Error, model.creation.value)
        assertEquals("巡检表", model.taskName.value)
        assertNull(model.createdTaskId.value)
        repository.failCreate = false
        model.createTask()
        runCurrent()
        assertEquals("task-2", model.createdTaskId.value)
        assertEquals("", model.taskName.value)
        model.creationOpened()
        assertNull(model.createdTaskId.value)
    }

    @Test
    fun `rapid repeated create does not insert twice`() = runTest(dispatcher) {
        val repository = TestRepository()
        val model = TaskViewModel(repository, SavedStateHandle())
        model.changeName("设备登记")
        model.createTask()
        model.createTask()
        runCurrent()
        model.createTask()
        runCurrent()
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun `saved draft name is restored`() {
        val model = TaskViewModel(TestRepository(), SavedStateHandle(mapOf("taskName" to "未完成名称")))
        assertEquals("未完成名称", model.taskName.value)
    }

    @Test
    fun `load error is distinct from empty list and supports retry`() = runTest(dispatcher) {
        val repository = TestRepository().apply { failLoad = true }
        val model = TaskViewModel(repository, SavedStateHandle())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.tasks.collect {} }
        runCurrent()
        assertEquals(TaskListState.Error, model.tasks.value)
        repository.failLoad = false
        model.retryLoading()
        runCurrent()
        assertEquals(TaskListState.Ready(emptyList()), model.tasks.value)
    }

    /** Test-only fake; production uses Room and propagates real storage failures. */
    private class TestRepository : TaskRepository {
        var failCreate = false
        var failLoad = false
        var createCalls = 0
        private val values = MutableStateFlow<List<DigitizationTask>>(emptyList())

        override fun observeTasks(): Flow<List<DigitizationTask>> = flow {
            if (failLoad) throw IOException("test read failure")
            values.collect { emit(it) }
        }
        override fun observeTask(id: String) = values.map { tasks -> tasks.find { it.id == id } }
        override suspend fun createTask(name: String): DigitizationTask {
            createCalls++
            if (failCreate) throw IOException("test write failure")
            val task = DigitizationTask("task-$createCalls", name, TaskStatus.DRAFT, Instant.EPOCH, Instant.EPOCH)
            values.value += task
            return task
        }
    }
}
