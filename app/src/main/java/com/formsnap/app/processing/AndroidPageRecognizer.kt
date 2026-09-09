package com.formsnap.app.processing

import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.domain.model.*
import com.formsnap.app.validation.IssueCode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor

class AndroidPageRecognizer(private val images: SourceImageLoader) : PageRecognizer {
    override suspend fun recognize(source: SourceDocument): CandidatePage = withContext(Dispatchers.Default) {
        val bitmap = images.load(source.sourceUri)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        // The task completion callback owns the bitmap after submission, even if a coroutine times out.
        var submitted = false
        try {
            val luminance = ByteArray(bitmap.width * bitmap.height)
            val pixels = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in pixels.indices) {
                    val pixel = pixels[x]
                    luminance[y * bitmap.width + x] = (((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000).toByte()
                }
            }
            val image = GrayImage(bitmap.width, bitmap.height, luminance)
            val grid = GridDetector().detect(image)
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            submitted = true
            val executor = Executor { it.run() }
            task.addOnCompleteListener(executor) { bitmap.recycle(); recognizer.close() }
            val recognized = withTimeout(60_000) {
                suspendCancellableCoroutine { continuation ->
                    task.addOnSuccessListener(executor) { if (continuation.isActive) continuation.resumeWith(Result.success(it)) }
                    task.addOnFailureListener(executor) { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
                    task.addOnCanceledListener(executor) { continuation.cancel() }
                }
            }
            val evidence = recognized.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
                val box = element.boundingBox ?: return@mapNotNull null
                val left = (box.left / width).coerceIn(0f, 1f)
                val top = (box.top / height).coerceIn(0f, 1f)
                val right = (box.right / width).coerceIn(0f, 1f)
                val bottom = (box.bottom / height).coerceIn(0f, 1f)
                if (left >= right || top >= bottom || element.text.isBlank()) null
                else TextEvidence(element.text, SourceRegion(left, top, right, bottom), element.confidence)
            }
            if (evidence.isEmpty()) throw PageRecognitionException(IssueCode.UNREADABLE, "没有读到可辨认的文字，请重新选择清晰页面。")
            TableCandidateAssembler().assemble(source.id, image, grid, evidence)
        } finally {
            if (!submitted) { bitmap.recycle(); recognizer.close() }
        }
    }
}
