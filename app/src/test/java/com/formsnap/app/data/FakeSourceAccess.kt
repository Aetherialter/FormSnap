package com.formsnap.app.data

import com.formsnap.app.data.source.SourceAccess
import com.formsnap.app.data.source.SourceAccessException
import com.formsnap.app.data.source.SourceMetadata
import com.formsnap.app.domain.repository.SourceImportFailure

/** Test double for OS grants only. Database tests still use a real file-backed Room database. */
internal class FakeSourceAccess : SourceAccess {
    val grants = mutableSetOf<String>()
    val inaccessible = mutableSetOf<String>()
    val denied = mutableSetOf<String>()
    val retained = mutableListOf<String>()
    val released = mutableListOf<String>()
    var failRelease = false
    var grantCapacity = Int.MAX_VALUE
    var beforeInspect: suspend (String) -> Unit = {}

    override suspend fun persistedReadUris() = grants.toSet()
    override suspend fun retainRead(uri: String) {
        retained += uri
        if (uri in denied) throw SourceAccessException(SourceImportFailure.PERMISSION_NOT_RETAINED)
        grants += uri
        while (grants.size > grantCapacity) grants.remove(grants.first())
    }
    override suspend fun inspectImage(uri: String): SourceMetadata {
        beforeInspect(uri)
        if (uri in inaccessible) throw SourceAccessException(SourceImportFailure.UNREADABLE)
        return SourceMetadata(uri.substringAfterLast('/') + ".png")
    }
    override suspend fun releaseRead(uri: String) {
        if (failRelease) error("test release failure")
        released += uri
        grants -= uri
    }
}
