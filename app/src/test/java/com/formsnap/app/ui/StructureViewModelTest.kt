package com.formsnap.app.ui

import androidx.lifecycle.SavedStateHandle
import com.formsnap.app.processing.*
import com.formsnap.app.review.StaleReviewException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class StructureViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun finish(){Dispatchers.resetMain()}
    private val grid=TableGrid(listOf(0,100,200),listOf(0,100,200),200,200)
    private val page=StoredStructure(StructureProposal("source",grid,emptyList(),emptyList(),listOf(listOf("甲"),listOf("乙")),0),false,1)
    private val repository=object: StructureRepository {
        var failFlow=false;var stale=false;var confirmed=0;var gate: CompletableDeferred<Unit>?=null
        override fun observe(taskId: String): Flow<List<StoredStructure>> { check(!failFlow);return flowOf(listOf(page)) }
        override suspend fun saveDraft(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int): StoredStructure {
            gate?.await();if(stale)throw StaleReviewException();return page.copy(revision=revision+1)
        }
        override suspend fun confirm(taskId: String,sourceId: String,grid: TableGrid,paths: List<List<String>>,revision: Int,discardHumanWork: Boolean) { confirmed++ }
    }
    private val processing=object: ProcessingGateway {
        var failFlow=false;var failEnqueue=false;var enqueued=0
        override fun observe(taskId: String): Flow<ProcessingActivity> { check(!failFlow);return flowOf(ProcessingActivity(false)) }
        override suspend fun enqueue(request: ProcessingRequest) { check(!failEnqueue);enqueued++ }
    }
    private fun TestScope.model(): StructureViewModel {
        val model=StructureViewModel("task",repository,processing,SavedStateHandle())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){model.state.collect{}}
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){model.activity.collect{}}
        return model
    }
    @Test fun `failed data and scheduler observations can be retried`()=runTest(dispatcher) {
        repository.failFlow=true;processing.failFlow=true
        val model=model();runCurrent()
        assertEquals(StructureUiState.Error,model.state.value);assertNull(model.activity.value)
        repository.failFlow=false;processing.failFlow=false;model.retry();runCurrent()
        assertEquals(StructureUiState.Ready(listOf(page)),model.state.value)
        assertEquals(ProcessingActivity(false),model.activity.value)
    }
    @Test fun `committed confirmation is reported truthfully when scheduling fails`()=runTest(dispatcher) {
        processing.failEnqueue=true
        val model=model();model.confirm(page,grid,page.proposal.headerPaths,false);runCurrent()
        assertEquals(1,repository.confirmed);assertEquals(0,processing.enqueued)
        assertTrue(model.notice.value!!.startsWith("结构已确认，但"));assertFalse(model.busy.value)
    }
    @Test fun `stale draft releases busy state and requests refreshed structure`()=runTest(dispatcher) {
        repository.stale=true
        val model=model();model.save(page,grid,page.proposal.headerPaths);runCurrent()
        assertFalse(model.busy.value);assertTrue(model.notice.value!!.startsWith("结构已变化"))
        assertEquals(0,repository.confirmed)
    }
    @Test fun `cancelled draft does not schedule work or leave editor busy`()=runTest(dispatcher) {
        val gate=CompletableDeferred<Unit>();repository.gate=gate
        val model=model();model.save(page,grid,page.proposal.headerPaths);runCurrent()
        assertTrue(model.busy.value)
        gate.cancel();runCurrent()
        assertFalse(model.busy.value);assertEquals(0,processing.enqueued);assertNull(model.notice.value)
    }
}
