package com.formsnap.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.formsnap.app.processing.*
import com.formsnap.app.domain.repository.HumanWorkExistsException
import com.formsnap.app.domain.repository.SchemaMismatchException
import com.formsnap.app.review.StaleReviewException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface StructureUiState {
    data object Loading: StructureUiState
    data object Error: StructureUiState
    data class Ready(val pages: List<StoredStructure>): StructureUiState
}
@OptIn(ExperimentalCoroutinesApi::class)
class StructureViewModel(val taskId: String,private val repository: StructureRepository,private val processing: ProcessingGateway,
    private val saved: SavedStateHandle): ViewModel() {
    private val reload=MutableStateFlow(0)
    val state=reload.flatMapLatest { flow { emitAll(repository.observe(taskId)) }.map<List<StoredStructure>,StructureUiState> { StructureUiState.Ready(it) }
        .onStart { emit(StructureUiState.Loading) }.catch { emit(StructureUiState.Error) } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),StructureUiState.Loading)
    private val mutableBusy=MutableStateFlow(false)
    val busy=mutableBusy.asStateFlow()
    private val mutableNotice=MutableStateFlow<String?>(null)
    val notice=mutableNotice.asStateFlow()
    val activity=reload.flatMapLatest { flow { emitAll(processing.observe(taskId)) }.map<ProcessingActivity,ProcessingActivity?> { it }
        .catch { mutableNotice.value="后台状态暂不可用，请重试。";emit(null) } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val selected=saved.getStateFlow("source","")
    fun select(id: String) { saved["source"]=id }
    fun retry() { reload.value++ }
    fun save(page: StoredStructure,grid: TableGrid,paths: List<List<String>>)=action {
        repository.saveDraft(taskId,page.proposal.sourceId,grid,paths,page.revision)
        mutableNotice.value="结构草稿已保存，覆盖图和字段路径已更新。"
    }
    fun confirm(page: StoredStructure,grid: TableGrid,paths: List<List<String>>,discard: Boolean)=action {
        repository.confirm(taskId,page.proposal.sourceId,grid,paths,page.revision,discard)
        // Confirmation has committed even when scheduling the remaining pages fails.
        try { processing.enqueue(ProcessingRequest(taskId)); mutableNotice.value="结构已确认，已安排后续页面继续整理。" }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { mutableNotice.value="结构已确认，但后续整理未能启动。请返回任务点击继续整理。" }
    }
    private fun action(block: suspend ()->Unit) {
        if(mutableBusy.value)return
        mutableBusy.value=true; mutableNotice.value=null
        viewModelScope.launch {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: StaleReviewException) { mutableNotice.value="结构已变化，请重新读取后再保存。"; retry() }
            catch(_: HumanWorkExistsException) { mutableNotice.value="此页包含人工修改。确认替换之前，现有数据保持不变。" }
            catch(_: SchemaMismatchException) { mutableNotice.value="此页字段路径与当前任务不一致，请核对表头，或将不同格式放入独立任务。" }
            catch(_: Exception) { mutableNotice.value="未能保存结构。请检查表头不要切开合并区域、分割与字段路径数量一致、来源可访问，并等待后台整理结束。" }
            finally { mutableBusy.value=false }
        }
    }
}
