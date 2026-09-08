package com.formsnap.app.ui

import androidx.lifecycle.viewModelScope
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.domain.model.SourceStatus
import com.formsnap.app.domain.repository.SourceImportResult
import com.formsnap.app.domain.repository.SourceRemovalResult
import com.formsnap.app.domain.repository.SourceRepository
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.observe(repository: TestSources): SourceViewModel {
        val model = SourceViewModel("task", repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.sources.collect {} }
        return model
    }

    @Test fun `cancelled picker result does not call repository`() = runTest(dispatcher) {
        val repository = TestSources()
        val model = observe(repository)
        model.add(emptyList())
        runCurrent()
        assertEquals(0, repository.addCalls)
        assertEquals(SourceListState.Ready(emptyList()), model.sources.value)
    }

    @Test fun `visible count follows source flow through add remove and viewmodel recreation`() = runTest(dispatcher) {
        val repository = TestSources()
        val model = observe(repository)
        model.add(listOf("a", "b"))
        runCurrent()
        val first = (model.sources.value as SourceListState.Ready).pages
        assertEquals(2, first.size)
        model.remove(first[0].id)
        runCurrent()
        val remaining = (model.sources.value as SourceListState.Ready).pages
        assertEquals(1, remaining.size)
        val recreated = observe(repository)
        runCurrent()
        assertEquals(SourceListState.Ready(remaining), recreated.sources.value)
        assertTrue(repository.taskIds.all { it == "task" })
    }

    @Test fun `failed add and delete show errors while preserving visible rows`() = runTest(dispatcher) {
        val repository = TestSources()
        val model = observe(repository)
        repository.failAdd = true
        model.add(listOf("a"))
        runCurrent()
        assertEquals(SourceNotice.AddFailed, model.notice.value)
        assertTrue((model.sources.value as SourceListState.Ready).pages.isEmpty())
        repository.failAdd = false
        model.add(listOf("a"))
        runCurrent()
        val before = model.sources.value
        repository.failRemove = true
        model.remove("a")
        runCurrent()
        assertEquals(SourceNotice.RemoveFailed, model.notice.value)
        assertEquals(before, model.sources.value)
        assertEquals(SourceOperation.IDLE, model.operation.value)
    }

    @Test fun `picker callback waits for resume refresh instead of being discarded`() = runTest(dispatcher) {
        val repository = TestSources()
        val gate = CompletableDeferred<Unit>()
        repository.refreshGate = gate
        val model = observe(repository)
        model.refresh()
        runCurrent()
        model.add(listOf("a"))
        runCurrent()
        assertEquals(SourceOperation.CHECKING, model.operation.value)
        assertEquals(0, repository.addCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, (model.sources.value as SourceListState.Ready).pages.size)
    }

    @Test fun `read failure is not zero pages and retry resubscribes`() = runTest(dispatcher) {
        val repository = TestSources().apply { failRead = true }
        val model = observe(repository)
        runCurrent()
        assertEquals(SourceListState.Error, model.sources.value)
        repository.failRead = false
        model.retryLoading()
        runCurrent()
        assertEquals(SourceListState.Ready(emptyList()), model.sources.value)
    }

    @Test fun `cancellation ends busy state without becoming a storage failure`() = runTest(dispatcher) {
        val repository = TestSources().apply { suspendAdd = true }
        val model = observe(repository)
        model.add(listOf("a"))
        runCurrent()
        assertEquals(SourceOperation.ADDING, model.operation.value)
        model.viewModelScope.cancel()
        runCurrent()
        assertEquals(SourceOperation.IDLE, model.operation.value)
        assertNull(model.notice.value)
    }

    private class TestSources : SourceRepository {
        private val pages = MutableStateFlow<List<SourceDocument>>(emptyList())
        var failAdd = false
        var failRemove = false
        var failRead = false
        var suspendAdd = false
        var refreshGate: CompletableDeferred<Unit>? = null
        var addCalls = 0
        val taskIds = mutableListOf<String>()

        override fun observeSources(taskId: String) = flow {
            if (failRead) throw IOException("test read failure")
            pages.collect { emit(it.filter { page -> page.taskId == taskId }) }
        }
        override suspend fun addSources(taskId: String, uris: List<String>): SourceImportResult {
            taskIds += taskId
            addCalls++
            if (suspendAdd) awaitCancellation()
            if (failAdd) throw IOException("test add failure")
            val added = uris.mapIndexed { index, uri ->
                SourceDocument(uri, taskId, uri, pages.value.size + index, SourceStatus.AVAILABLE, Instant.EPOCH)
            }
            pages.value += added
            return SourceImportResult(added.size, 0, emptyList())
        }
        override suspend fun removeSource(taskId: String, sourceId: String): SourceRemovalResult {
            taskIds += taskId
            if (failRemove) throw IOException("test delete failure")
            pages.value = pages.value.filterNot { it.taskId == taskId && it.id == sourceId }
            return SourceRemovalResult(true)
        }
        override suspend fun refreshAvailability(taskId: String) { refreshGate?.await() }
    }
}
