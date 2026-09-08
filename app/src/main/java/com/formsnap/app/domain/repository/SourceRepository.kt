package com.formsnap.app.domain.repository

import com.formsnap.app.domain.model.SourceDocument
import kotlinx.coroutines.flow.Flow

interface SourceRepository {
    fun observeSources(taskId: String): Flow<List<SourceDocument>>
    suspend fun addSources(taskId: String, uris: List<String>): SourceImportResult
    suspend fun removeSource(taskId: String, sourceId: String): SourceRemovalResult
    suspend fun refreshAvailability(taskId: String)
}

enum class SourceImportFailure { PERMISSION_NOT_RETAINED, UNREADABLE, UNSUPPORTED_SOURCE, TIMEOUT }
data class SourceRejection(val uri: String, val reason: SourceImportFailure)
data class SourceImportResult(
    val added: Int,
    val duplicates: Int,
    val rejected: List<SourceRejection>,
    val permissionCleanupPending: Boolean = false,
)
data class SourceRemovalResult(val removed: Boolean, val permissionCleanupPending: Boolean = false)
