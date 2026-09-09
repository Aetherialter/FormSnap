package com.formsnap.app.data.source

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.CancellationSignal
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Bounded in-memory decoding only. Coordinates refer to the EXIF-upright original image. */
class SourceImageLoader(private val resolver: ContentResolver) {
    suspend fun load(uri: String, maxDimension: Int = 2200): Bitmap = withTimeout(30_000) {
        require(maxDimension in 128..2400)
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                try {
                    val bitmap = decode(uri, maxDimension, signal)
                    continuation.resume(bitmap) { _, image, _ -> image.recycle() }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
            }
        }
    }

    private fun decode(value: String, maxDimension: Int, signal: CancellationSignal): Bitmap {
        val uri = Uri.parse(value)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        fun <T> read(block: (java.io.InputStream) -> T): T {
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("Source cannot be opened")
            // Bounds-only BitmapFactory decoding deliberately returns null. That is not an open failure.
            return descriptor.use { it.createInputStream().use(block) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 250_000_000L) throw IOException("Unsupported image dimensions")
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > maxDimension) {
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
        }
        val orientation = read { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val decoded = read { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("Source is not a decodable image")
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return try {
            val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (upright !== decoded) decoded.recycle()
            upright
        } catch (failure: Throwable) { decoded.recycle(); throw failure }
    }
}
