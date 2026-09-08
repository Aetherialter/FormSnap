package com.formsnap.app.data.source

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.formsnap.app.domain.repository.SourceImportFailure
import java.io.FileNotFoundException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class AndroidSourceAccess(
    private val resolver: ContentResolver,
    private val requestTimeoutMillis: Long = 10_000,
) : SourceAccess {
    override suspend fun persistedReadUris(): Set<String> = providerCall {
        resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    }

    override suspend fun retainRead(uri: String) = providerCall {
        val source = contentUri(uri)
        try {
            resolver.takePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (resolver.persistedUriPermissions.none { it.uri == source && it.isReadPermission }) {
                throw SourceAccessException(SourceImportFailure.PERMISSION_NOT_RETAINED)
            }
        } catch (_: SecurityException) {
            throw SourceAccessException(SourceImportFailure.PERMISSION_NOT_RETAINED)
        }
    }

    override suspend fun inspectImage(uri: String): SourceMetadata = providerCall { signal ->
        val source = contentUri(uri)
        try {
            if (resolver.getType(source)?.startsWith("image/") != true) {
                throw SourceAccessException(SourceImportFailure.UNSUPPORTED_SOURCE)
            }
            // Open a real descriptor, without decoding or copying the image. This is access checking,
            // not a claim that the page is clear, structurally valid, or suitable for recognition.
            resolver.openAssetFileDescriptor(source, "r", signal)?.use { descriptor ->
                if (descriptor.declaredLength == 0L) throw FileNotFoundException()
            } ?: throw FileNotFoundException()
            val name = try {
                resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
                    val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && it.moveToFirst()) it.getString(column) else null
                }
            } catch (_: Exception) {
                // A provider's optional display name must not determine read access.
                null
            }
            SourceMetadata(name?.takeIf { it.isNotBlank() })
        } catch (error: SourceAccessException) {
            throw error
        } catch (_: SecurityException) {
            throw SourceAccessException(SourceImportFailure.UNREADABLE)
        } catch (_: java.io.IOException) {
            throw SourceAccessException(SourceImportFailure.UNREADABLE)
        }
    }

    override suspend fun releaseRead(uri: String) = providerCall {
        resolver.releasePersistableUriPermission(contentUri(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun contentUri(value: String): Uri = Uri.parse(value).also {
        if (it.scheme != ContentResolver.SCHEME_CONTENT || it.authority.isNullOrBlank()) {
            throw SourceAccessException(SourceImportFailure.UNSUPPORTED_SOURCE)
        }
    }

    /** Return control on a stalled provider; cancellation is also forwarded to Android's provider API.
     * A provider ignoring cancellation may finish later; its descriptors still close via use.
     * Permission reconciliation in the repository handles a grant that finishes after cancellation.
     */
    private suspend fun <T : Any> providerCall(block: (CancellationSignal) -> T): T {
        val result = withTimeoutOrNull(requestTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                    if (continuation.isActive) {
                        val outcome = runCatching { block(signal) }
                        if (continuation.isActive) continuation.resumeWith(outcome)
                    }
                }
            }
        }
        return result ?: throw SourceAccessException(SourceImportFailure.TIMEOUT)
    }
}
