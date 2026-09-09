package com.formsnap.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.formsnap.app.domain.model.FieldDefinition
import com.formsnap.app.domain.repository.QualityRepository
import com.formsnap.app.domain.repository.SourceRepository
import com.formsnap.app.export.ExportOutcome
import com.formsnap.app.export.TaskExporter
import com.formsnap.app.processing.*
import com.formsnap.app.review.ReviewDecision
import com.formsnap.app.review.StaleReviewException
import com.formsnap.app.validation.QualitySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface WorkflowState {
    data object Loading : WorkflowState
    data object Error : WorkflowState
    data class Ready(val snapshot: QualitySnapshot) : WorkflowState
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowViewModel(
    val taskId: String,
    private val repository: QualityRepository,
    private val sources: SourceRepository,
    private val processing: ProcessingGateway,
    private val exporter: TaskExporter,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    val state = reload.flatMapLatest {
        flow { emitAll(repository.observeQuality(taskId)) }.map<QualitySnapshot, WorkflowState> { WorkflowState.Ready(it) }
            .onStart { emit(WorkflowState.Loading) }.catch { emit(WorkflowState.Error) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkflowState.Loading)
    val activity = reload.flatMapLatest {
        flow { emitAll(processing.observe(taskId)) }.map<ProcessingActivity, ProcessingActivity?> { it }
            .catch { mutableNotice.value = "无法读取后台任务状态，请重试。"; emit(null) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableNotice = MutableStateFlow<String?>(null)
    private val actions = Mutex()
    private var pendingActions = 0
    val notice = mutableNotice.asStateFlow()
    val exportRequested = savedState.getStateFlow("exportRequested", false)
    val search = savedState.getStateFlow("search", "")

    fun search(value: String) { savedState["search"] = value }
    fun clearNotice() { mutableNotice.value = null }
    fun retry() { reload.update { it + 1 }; refresh() }
    fun refresh() = action("检查尚未完成，请重试。") {
        sources.refreshAvailability(taskId)
        repository.revalidate(taskId)
    }
    fun start(sourceId: String? = null, discardHumanWork: Boolean = false) = action("未能开始整理，请重试。") {
        processing.enqueue(ProcessingRequest(taskId, sourceId, discardHumanWork))
        mutableNotice.value = "已安排整理，可离开此页面，稍后返回查看结果。"
    }
    fun fields(fields: List<FieldDefinition>) = action("字段设置未能保存，请检查输入后重试。") {
        repository.updateFields(taskId, fields)
        mutableNotice.value = "字段设置已保存，数据已重新检查。"
    }
    fun decide(decision: ReviewDecision, onSaved: () -> Unit = {}) = action("处理结果未能保存，请重试。") { repository.decide(taskId, decision); onSaved() }
    fun requestExport() = action("尚有未处理问题或来源不可用，请先完成检查。") {
        exporter.checkReady(taskId)
        savedState["exportRequested"] = true
    }
    fun exportDialogOpened() { savedState["exportRequested"] = false }
    fun exportPickerUnavailable() { savedState["exportRequested"] = false; mutableNotice.value = "无法打开系统保存窗口，请检查设备的文件应用后重试。" }
    fun saveExport(uri: String?) {
        savedState["exportRequested"] = false
        if (uri == null) return
        action("保存未完成，目标位置可能存在不完整文件，请重新导出。", queued = true) {
            mutableNotice.value = when (exporter.export(taskId, uri)) {
                ExportOutcome.EXPORTED -> "XLSX 已保存，包含最终数据和复核记录。"
                ExportOutcome.DATA_CHANGED -> "文件已生成，但整理数据在保存期间发生变化，请重新检查并导出。"
            }
        }
    }
    private fun action(failureMessage: String, queued: Boolean = false, operation: suspend () -> Unit) {
        if (mutableBusy.value && !queued) return
        pendingActions++
        mutableBusy.value = true
        mutableNotice.value = null
        viewModelScope.launch {
            actions.withLock {
                try { operation() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: StaleReviewException) { mutableNotice.value = "数据已变化，请重新查看当前项后处理。" }
                catch (_: Exception) { mutableNotice.value = failureMessage }
                finally { pendingActions--; mutableBusy.value = pendingActions > 0 }
            }
        }
    }
}
