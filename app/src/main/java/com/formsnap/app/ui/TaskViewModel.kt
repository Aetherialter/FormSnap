package com.formsnap.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.repository.TaskRepository
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

sealed interface TaskListState {
    data object Loading : TaskListState
    data class Ready(val tasks: List<DigitizationTask>) : TaskListState
    data object Error : TaskListState
}

sealed interface CreationState {
    data object Idle : CreationState
    data object Saving : CreationState
    data object Error : CreationState
}

sealed interface TaskDetailState {
    data object Loading : TaskDetailState
    data class Ready(val task: DigitizationTask?) : TaskDetailState
    data object Error : TaskDetailState
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val repository: TaskRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val reload = MutableStateFlow(0)
    val tasks = reload.flatMapLatest {
        repository.observeTasks()
            .map<List<DigitizationTask>, TaskListState> { TaskListState.Ready(it) }
            .onStart { emit(TaskListState.Loading) }
            .catch { emit(TaskListState.Error) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskListState.Loading)

    fun observeTask(id: String) = reload.flatMapLatest {
        repository.observeTask(id)
            .map<DigitizationTask?, TaskDetailState> { TaskDetailState.Ready(it) }
            .onStart { emit(TaskDetailState.Loading) }
            .catch { emit(TaskDetailState.Error) }
    }

    val taskName = savedStateHandle.getStateFlow("taskName", "")
    val createdTaskId = savedStateHandle.getStateFlow<String?>("createdTaskId", null)
    private val mutableCreation = MutableStateFlow<CreationState>(CreationState.Idle)
    val creation = mutableCreation.asStateFlow()

    fun changeName(name: String) {
        if (mutableCreation.value == CreationState.Saving) return
        savedStateHandle["taskName"] = name
        mutableCreation.value = CreationState.Idle
    }

    fun createTask() {
        if (mutableCreation.value == CreationState.Saving || createdTaskId.value != null) return
        val name = taskName.value.trim()
        if (name.isBlank() || name.length > DigitizationTask.MAX_NAME_LENGTH) return
        mutableCreation.value = CreationState.Saving
        viewModelScope.launch {
            try {
                val task = repository.createTask(name)
                savedStateHandle["createdTaskId"] = task.id
                savedStateHandle["taskName"] = ""
                mutableCreation.value = CreationState.Idle
            } catch (cancelled: CancellationException) {
                mutableCreation.value = CreationState.Idle
                throw cancelled
            } catch (_: Exception) {
                mutableCreation.value = CreationState.Error
            }
        }
    }

    fun creationOpened() {
        savedStateHandle["createdTaskId"] = null
    }

    fun retryLoading() {
        reload.update { it + 1 }
    }
}
