package com.formsnap.app.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowContentResolver

/** Local fixture provider. It simulates provider responses, not Android's real SAF permission enforcement. */
class TestImageProvider : ContentProvider() {
    lateinit var original: File
    var unavailable = false
    var stall = false
    var cancellationReceived = false

    override fun onCreate(): Boolean {
        original = File(requireNotNull(context).cacheDir, "phase1-provider-fixture.png")
        // A test fixture only; the production importer never writes or copies originals.
        original.writeBytes(android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=", 0,
        ))
        return true
    }
    override fun getType(uri: Uri) = if (uri.lastPathSegment == "pdf") "application/pdf" else "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("页面-${uri.lastPathSegment}.png")) }

    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor {
        if (stall) {
            val cancelled = CountDownLatch(1)
            signal?.setOnCancelListener { cancellationReceived = true; cancelled.countDown() }
            cancelled.await(2, TimeUnit.SECONDS)
            signal?.throwIfCanceled()
        }
        if (unavailable || !original.exists()) throw FileNotFoundException()
        check(mode == "r")
        return AssetFileDescriptor(ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY), 0, original.length())
    }
    override fun openAssetFile(uri: Uri, mode: String) = openAssetFile(uri, mode, null)
    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?) =
        openAssetFile(uri, "r", signal)
    override fun insert(uri: Uri, values: ContentValues?): Uri = error("fixture is read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("original must never be deleted")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("fixture is read-only")

    companion object {
        const val AUTHORITY = "formsnap.fixture"
        fun register(context: Context): TestImageProvider = TestImageProvider().also { provider ->
            provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
            ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        }
    }
}
