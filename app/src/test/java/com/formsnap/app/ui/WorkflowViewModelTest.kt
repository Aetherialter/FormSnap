package com.formsnap.app.ui

import androidx.lifecycle.SavedStateHandle
import com.formsnap.app.domain.model.*
import com.formsnap.app.domain.repository.*
import com.formsnap.app.export.*
import com.formsnap.app.processing.*
import com.formsnap.app.review.ReviewDecision
import com.formsnap.app.validation.QualitySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun finish() { Dispatchers.resetMain() }
    private class Quality : QualityRepository {
        var fail = false
        val snapshot = QualitySnapshot(null, emptyList())
        override fun observeCounts() = flowOf(emptyList<TaskCounts>())
        override fun observeQuality(taskId: String): Flow<QualitySnapshot> {
            if (fail) error("fixture flow construction failure")
            return flowOf(snapshot)
        }
        override suspend fun revalidate(taskId: String) = snapshot
        override suspend fun updateFields(taskId: String, fields: List<FieldDefinition>) = error("Not used")
        override suspend fun decide(taskId: String, decision: ReviewDecision) = error("Not used")
        override suspend fun exportableDataset(taskId: String) = error("Not used")
    }
    private class Sources : SourceRepository {
        var gate: CompletableDeferred<Unit>? = null
        override fun observeSources(taskId: String) = flowOf(emptyList<SourceDocument>())
        override suspend fun addSources(taskId: String, uris: List<String>) = error("Not used")
        override suspend fun removeSource(taskId: String, sourceId: String) = error("Not used")
        override suspend fun refreshAvailability(taskId: String) { gate?.await() }
    }
    private class Exporter : TaskExporter {
        var fail = false
        val saved = mutableListOf<String>()
        override suspend fun checkReady(taskId: String) { check(!fail) }
        override suspend fun export(taskId: String, uri: String): ExportOutcome { saved += uri; return ExportOutcome.EXPORTED }
    }
    private val quality = Quality()
    private val sources = Sources()
    private val exporter = Exporter()
    private val processing = object : ProcessingGateway {
        override fun observe(taskId: String) = flowOf(ProcessingActivity(false))
        override suspend fun enqueue(request: ProcessingRequest) = error("Not used")
    }
    private fun TestScope.model(): WorkflowViewModel {
        val model = WorkflowViewModel("task", quality, sources, processing, exporter, SavedStateHandle())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        return model
    }

    @Test fun `flow construction failure can be retried without recreating viewmodel`() = runTest(dispatcher) {
        quality.fail = true
        val model = model()
        runCurrent()
        assertEquals(WorkflowState.Error, model.state.value)
        quality.fail = false
        model.retry()
        runCurrent()
        assertEquals(WorkflowState.Ready(quality.snapshot), model.state.value)
    }
    @Test fun `export result waits for resume refresh and uri is preserved`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        sources.gate = gate
        val model = model()
        model.refresh()
        runCurrent()
        model.saveExport("content://export/selected")
        runCurrent()
        assertTrue(model.busy.value)
        assertTrue(exporter.saved.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("content://export/selected"), exporter.saved)
        assertFalse(model.busy.value)
    }
    @Test fun `cancelled destination does not export and failed preflight does not open picker`() = runTest(dispatcher) {
        val model = model()
        model.saveExport(null)
        runCurrent()
        assertTrue(exporter.saved.isEmpty())
        exporter.fail = true
        model.requestExport()
        runCurrent()
        assertFalse(model.exportRequested.value)
        assertNotNull(model.notice.value)
        exporter.fail = false
        model.requestExport()
        runCurrent()
        assertTrue(model.exportRequested.value)
    }
}
