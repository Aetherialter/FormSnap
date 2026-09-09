package com.formsnap.app.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.formsnap.app.export.XlsxWriter
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.shadows.ShadowContentResolver

class TestExportProvider : ContentProvider() {
    lateinit var output: File
    var fail = false
    var beforeOpen: () -> Unit = {}
    override fun onCreate(): Boolean { output = File(requireNotNull(context).cacheDir, "export-fixture.xlsx"); return true }
    override fun getType(uri: Uri) = XlsxWriter.MIME
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (fail) throw FileNotFoundException("test output failure")
        beforeOpen()
        return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Not used")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Never delete export destinations automatically")
    companion object {
        const val URI = "content://formsnap.export.fixture/result.xlsx"
        fun register(context: Context) = TestExportProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = "formsnap.export.fixture" })
            ShadowContentResolver.registerProviderInternal("formsnap.export.fixture", it)
        }
    }
}
