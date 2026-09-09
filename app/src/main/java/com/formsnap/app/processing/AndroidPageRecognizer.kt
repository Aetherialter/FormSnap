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

class AndroidPageRecognizer(private val images: SourceImageLoader) : StructureRecognizer {
    override suspend fun inspect(source: SourceDocument): StructureProposal = withContext(Dispatchers.Default) {
        val bitmap = images.load(source.sourceUri)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        // The task completion callback owns the bitmap after submission, even if a coroutine times out.
        var submitted = false
        try {
            val image = bitmap.gray()
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
            TableCandidateAssembler().propose(source.id, image, grid, evidence)
        } finally {
            if (!submitted) { bitmap.recycle(); recognizer.close() }
        }
    }

    override suspend fun revise(source: SourceDocument, grid: TableGrid, text: List<TextEvidence>): StructureProposal = withContext(Dispatchers.Default) {
        val bitmap=images.load(source.sourceUri)
        try { TableCandidateAssembler().propose(source.id,bitmap.gray(),grid,text,inferHeader=false) }
        finally { bitmap.recycle() }
    }
}

private fun android.graphics.Bitmap.gray(): GrayImage {
    val luminance=ByteArray(width*height); val pixels=IntArray(width)
    for(y in 0 until height) {
        getPixels(pixels,0,width,0,y,width,1)
        for(x in pixels.indices) {
            val p=pixels[x]
            luminance[y*width+x]=(((p shr 16 and 255)*299+(p shr 8 and 255)*587+(p and 255)*114)/1000).toByte()
        }
    }
    return GrayImage(width,height,luminance)
}
