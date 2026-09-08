package com.formsnap.app.domain.repository

import com.formsnap.app.domain.model.DigitizationTask
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeTasks(): Flow<List<DigitizationTask>>
    fun observeTask(id: String): Flow<DigitizationTask?>

    /** Returns only after the task is persisted. Invalid names and storage failures propagate. */
    suspend fun createTask(name: String): DigitizationTask
}
