package com.formsnap.app.data.source

import com.formsnap.app.domain.repository.SourceImportFailure

/** Platform boundary: only URI grants and read access, never original-file deletion or copying. */
interface SourceAccess {
    suspend fun persistedReadUris(): Set<String>
    suspend fun retainRead(uri: String)
    suspend fun inspectImage(uri: String): SourceMetadata
    suspend fun releaseRead(uri: String)
}

data class SourceMetadata(val displayName: String?)
class SourceAccessException(val reason: SourceImportFailure) : Exception()
