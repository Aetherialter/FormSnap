package com.formsnap.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.domain.repository.SourceImportResult
import com.formsnap.app.domain.repository.SourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SourceListState {
    data object Loading : SourceListState
    data class Ready(val pages: List<SourceDocument>) : SourceListState
    data object Error : SourceListState
}
enum class SourceOperation { IDLE, ADDING, REMOVING, CHECKING }
sealed interface SourceNotice {
    data class Imported(val result: SourceImportResult) : SourceNotice
    data object Removed : SourceNotice
    data object CleanupPending : SourceNotice
    data object AddFailed : SourceNotice
    data object RemoveFailed : SourceNotice
    data object CheckFailed : SourceNotice
    data object PickerUnavailable : SourceNotice
}

@OptIn(ExperimentalCoroutinesApi::class)
class SourceViewModel(
    private val taskId: String,
    private val repository: SourceRepository,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    private val actions = Mutex()
    private val mutableOperation = MutableStateFlow(SourceOperation.IDLE)
    val operation = mutableOperation.asStateFlow()
    private val mutableNotice = MutableStateFlow<SourceNotice?>(null)
    val notice = mutableNotice.asStateFlow()
    val sources = reload.flatMapLatest {
        repository.observeSources(taskId)
            .map<List<SourceDocument>, SourceListState> { SourceListState.Ready(it) }
            .onStart { emit(SourceListState.Loading) }
            .catch { emit(SourceListState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceListState.Loading)

    fun add(uris: List<String>) {
        if (uris.isEmpty()) return
        // A picker callback is never discarded just because ON_RESUME is checking existing pages.
        runAction(SourceOperation.ADDING, SourceNotice.AddFailed) {
            mutableNotice.value = SourceNotice.Imported(repository.addSources(taskId, uris))
        }
    }

    fun remove(sourceId: String) = runAction(SourceOperation.REMOVING, SourceNotice.RemoveFailed) {
        val result = repository.removeSource(taskId, sourceId)
        mutableNotice.value = if (result.permissionCleanupPending) SourceNotice.CleanupPending else SourceNotice.Removed
    }

    fun refresh() = runAction(SourceOperation.CHECKING, SourceNotice.CheckFailed) {
        repository.refreshAvailability(taskId)
    }

    fun retryLoading() {
        reload.update { it + 1 }
        refresh()
    }

    fun pickerUnavailable() { mutableNotice.value = SourceNotice.PickerUnavailable }

    private fun runAction(operation: SourceOperation, failure: SourceNotice, block: suspend () -> Unit) {
        viewModelScope.launch {
            actions.withLock {
                mutableOperation.value = operation
                if (operation != SourceOperation.CHECKING) mutableNotice.value = null
                try {
                    block()
                    if (operation == SourceOperation.CHECKING && mutableNotice.value == SourceNotice.CheckFailed) {
                        mutableNotice.value = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableNotice.value = failure
                } finally {
                    mutableOperation.value = SourceOperation.IDLE
                }
            }
        }
    }
}
