package com.formsnap.app.domain

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.formsnap.app.processing.CandidateAxis
import com.formsnap.app.processing.CandidateDisposition
import com.formsnap.app.processing.ClippingEvidence
import com.formsnap.app.processing.GridCell
import com.formsnap.app.processing.GrayImage
import com.formsnap.app.processing.GridDetector
import com.formsnap.app.processing.StructureDetection
import com.formsnap.app.processing.StructureOutcome
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeoutException
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local-only sanity regression for user-provided photographs. The photographs are intentionally
 * outside test resources and are loaded from a configurable directory. Predictions are reported
 * beside proposed annotations; no prediction is promoted to ground truth here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealSampleRegressionTest {
    private data class TimedDetection(val detection: StructureDetection?, val timedOut: Boolean, val elapsedMs: Long)

    private val sampleIds = listOf("02", "03", "04", "05")
    private val timeoutMs = 20_000L
    private val previousBest = mapOf(
        "02" to (31 to 7),
        "03" to (29 to 5),
        "04" to (23 to 4),
        "05" to (37 to 5),
    )

    @Test
    fun `real sample structure sanity stays bounded and writes local overlays`() {
        val root = resolveSamplesRoot()
        assumeTrue("Real sample directory is not available: ${root.absolutePath}", root.isDirectory)
        val files = sampleIds.map { id -> root.resolve("real-sample-$id.jpg") }
        assumeTrue("Expected real sample batch is incomplete under ${root.absolutePath}", files.all(File::isFile))

        val annotationRoot = checkNotNull(root.parentFile).resolve("raw-annotations")
        val overlayRoot = checkNotNull(root.parentFile).resolve("debug-overlays").apply { mkdirs() }
        val reportRows = mutableListOf<String>()
        val timedOut = mutableListOf<String>()
        val regressions = mutableListOf<String>()

        for (file in files) {
            val sampleId = "REAL_SAMPLE_${file.nameWithoutExtension.substringAfterLast('-').padStart(2, '0')}"
            val annotation = JSONObject(annotationRoot.resolve("${file.nameWithoutExtension}.json").readText())
            check(annotation.getString("sampleId") == sampleId) { "Annotation/sample mismatch for $sampleId" }
            check(annotation.getString("annotationStatus") == "PROPOSED") {
                "$sampleId must remain PROPOSED until independently verified"
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assumeTrue("Unable to decode $sampleId", bitmap != null)
            val source = bitmap!!
            val image = source.toGrayImage()
            val first = detect(image, sampleId)
            val repeat = first.detection?.let { detect(image, sampleId) }
            if (first.timedOut || repeat?.timedOut == true) timedOut += sampleId
            if (first.detection != null) writeOverlay(source, first.detection, overlayRoot.resolve("${file.nameWithoutExtension}.png"))
            source.recycle()

            val detection = first.detection
            val diagnostics = detection?.diagnostics
            val grid = detection?.grid
            if(grid==null || detection.outcome!=StructureOutcome.STRUCTURE_REVIEW_REQUIRED)
                regressions += "$sampleId lost its safe review result"
            if(repeat==null || !sameStructure(detection,repeat.detection))
                regressions += "$sampleId changed on repeated detection"
            if(diagnostics!=null) {
                if(diagnostics.horizontalCandidates.count { it.disposition==CandidateDisposition.SELECTED }!=diagnostics.horizontalSelectedLines ||
                    diagnostics.verticalCandidates.count { it.disposition==CandidateDisposition.SELECTED }!=diagnostics.verticalSelectedLines)
                    regressions += "$sampleId candidate diagnostics disagree with the selected grid"
            }
            val rawH=diagnostics?.horizontalCandidateLines ?: 0
            val rawV=diagnostics?.verticalCandidateLines ?: 0
            val mergedH=diagnostics?.horizontalMergedCandidateLines ?: 0
            val mergedV=diagnostics?.verticalMergedCandidateLines ?: 0
            val candidateStatus=when {
                first.timedOut -> "TIMEOUT"
                detection==null -> "UNMEASURED"
                diagnostics!!.mergedCandidateCount>=350 || diagnostics.candidateReductionRatio<.35 -> "CANDIDATE_EXPLOSION_REVIEW"
                else -> "BOUNDED"
            }
            val clippingEvidence=diagnostics?.clippingEvidence?.name ?: "UNMEASURED"
            val clippingReasons=diagnostics?.clippingEvidenceReasons.orEmpty().joinToString(";").replace("\t"," ").replace("\n"," ")
            val stability = when {
                first.timedOut || detection == null || repeat == null -> "UNMEASURED"
                repeat.timedOut -> "REPEAT_TIMEOUT"
                sameStructure(detection, repeat.detection) -> "REPEAT_STABLE"
                else -> "REPEAT_CHANGED"
            }
            val previous = previousBest.getValue(file.nameWithoutExtension.substringAfterLast('-'))
            reportRows += listOf(
                sampleId,
                "32,35,205",
                annotation.getString("annotationStatus"),
                detection?.outcome?.name ?: "TIMEOUT",
                rawH.toString(),
                rawV.toString(),
                mergedH.toString(),
                mergedV.toString(),
                grid?.rows?.toString() ?: "NA",
                grid?.columns?.toString() ?: "NA",
                diagnostics?.rejectedLineCandidates?.toString() ?: "NA",
                diagnostics?.candidateReductionRatio?.let { "%.3f".format(java.util.Locale.ROOT,it) } ?: "NA",
                candidateStatus,
                clippingEvidence,
                clippingReasons.ifEmpty { "NA" },
                previous.first.toString(),
                previous.second.toString(),
                grid?.rows?.let { (it - previous.first).toString() } ?: "NA",
                grid?.columns?.let { (it - previous.second).toString() } ?: "NA",
                first.elapsedMs.toString(),
                (first.timedOut || repeat?.timedOut == true).toString(),
                stability,
                "compared_to_previous_best",
                annotation.getInt("expectedLeafColumns").toString(),
                annotation.getInt("expectedHeaderDepth").toString(),
                annotation.getString("expectedStructureOutcome"),
            ).joinToString("\t")
        }

        File("build/reports/structure").mkdirs()
        File("build/reports/structure/real-sample-regression.tsv").writeText(
            buildString {
                appendLine(
                    "sampleId\tpolicy\tannotationStatus\tstatus\trawH\trawV\tmergedH\tmergedV\tselectedRows\tselectedColumns" +
                        "\trejected\tcandidateReductionRatio\tcandidateExplosion\tclippingEvidence\tclippingReasons" +
                        "\tpreviousRows\tpreviousColumns\trowDelta\tcolumnDelta\tprocessingMs\ttimeout" +
                        "\tstability\tbaselineChange\tproposedLeafColumns\tproposedHeaderDepth\tproposedOutcome",
                )
                reportRows.forEach(::appendLine)
            },
        )
        assertFalse("Real sample regression timed out: ${timedOut.distinct().joinToString()}", timedOut.isNotEmpty())
        assertFalse(regressions.joinToString("; "),regressions.isNotEmpty())
    }

    private fun detect(image: GrayImage, sampleId: String): TimedDetection {
        val started = System.nanoTime()
        return try {
            TimedDetection(
                GridDetector {
                    if ((System.nanoTime() - started) / 1_000_000L > timeoutMs) {
                        throw TimeoutException("$sampleId exceeded ${timeoutMs}ms")
                    }
                }.recover(image),
                timedOut = false,
                elapsedMs = (System.nanoTime() - started) / 1_000_000L,
            )
        } catch (error: TimeoutException) {
            TimedDetection(null, timedOut = true, elapsedMs = (System.nanoTime() - started) / 1_000_000L)
        }
    }

    private fun sameStructure(first: StructureDetection, second: StructureDetection?): Boolean {
        if (second == null) return false
        return first.outcome == second.outcome && first.grid?.rows == second.grid?.rows && first.grid?.columns == second.grid?.columns
    }

    private fun resolveSamplesRoot(): File {
        val configured = System.getProperty("formsnap.realSamplesDir")
            ?: System.getenv("FORMSNAP_REAL_SAMPLES_DIR")
        if (configured != null) return File(configured)
        return sequenceOf(
            File("real-world-table-fixtures/raw-real-samples"),
            File("../real-world-table-fixtures/raw-real-samples"),
        ).firstOrNull(File::isDirectory) ?: File("real-world-table-fixtures/raw-real-samples")
    }

    private fun Bitmap.toGrayImage(): GrayImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return GrayImage(width, height, ByteArray(pixels.size) { index -> (pixels[index] and 255).toByte() })
    }

    private fun writeOverlay(source: Bitmap, detection: StructureDetection, destination: File) {
        val overlay = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)
        drawCandidateDiagnostics(canvas, detection.diagnostics, overlay.width, overlay.height)
        val grid = detection.grid
        if (grid != null) {
            val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.MAGENTA
                style = Paint.Style.STROKE
                strokeWidth = (overlay.width / 420f).coerceAtLeast(3f)
            }
            val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0, 210, 255)
                style = Paint.Style.STROKE
                strokeWidth = (overlay.width / 700f).coerceAtLeast(2f)
            }
            val columnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(40, 255, 80)
                style = Paint.Style.STROKE
                strokeWidth = (overlay.width / 700f).coerceAtLeast(2f)
            }
            val mergedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW
                style = Paint.Style.STROKE
                strokeWidth = (overlay.width / 500f).coerceAtLeast(3f)
            }
            drawCell(canvas, grid, GridCell(0, 0, grid.rows, grid.columns), tablePaint, overlay.width, overlay.height)
            for (row in grid.ys) drawSegment(canvas, grid.toImage(grid.xs.first().toFloat(), row.toFloat()), grid.toImage(grid.xs.last().toFloat(), row.toFloat()), rowPaint, overlay.width, overlay.height)
            for (column in grid.xs) drawSegment(canvas, grid.toImage(column.toFloat(), grid.ys.first().toFloat()), grid.toImage(column.toFloat(), grid.ys.last().toFloat()), columnPaint, overlay.width, overlay.height)
            for (merged in grid.merged) drawCell(canvas, grid, merged, mergedPaint, overlay.width, overlay.height)
            drawCell(canvas, grid, GridCell(0, 0, grid.headerEnd, grid.columns), mergedPaint, overlay.width, overlay.height)
        }
        FileOutputStream(destination).use { output -> check(overlay.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        overlay.recycle()
    }

    private fun drawCandidateDiagnostics(
        canvas: Canvas,
        diagnostics: com.formsnap.app.processing.StructureDiagnostics,
        width: Int,
        height: Int,
    ) {
        fun paint(color: Int, alpha: Int, strokeWidth: Float)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color=Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color))
            style=Paint.Style.STROKE
            this.strokeWidth=strokeWidth
        }
        val selected=paint(Color.GREEN,190,(width/520f).coerceAtLeast(3f))
        val merged=paint(Color.YELLOW,150,(width/800f).coerceAtLeast(2f))
        val high=paint(Color.RED,135,(width/900f).coerceAtLeast(1.5f))
        val low=paint(Color.GRAY,80,(width/1100f).coerceAtLeast(1f))
        fun draw(candidate: com.formsnap.app.processing.LineCandidateDiagnostic) {
            val candidatePaint=when(candidate.disposition) {
                CandidateDisposition.SELECTED -> selected
                CandidateDisposition.MERGED -> merged
                CandidateDisposition.REJECTED_HIGH_SCORE -> high
                CandidateDisposition.REJECTED_LOW_SCORE -> low
            }
            val breadth=if(candidate.axis==CandidateAxis.HORIZONTAL)height else width
            val startPosition=candidate.normalizedStartPosition*breadth
            val endPosition=candidate.normalizedEndPosition*breadth
            val start=(candidate.normalizedStart.coerceIn(0f,1f))*if(candidate.axis==CandidateAxis.HORIZONTAL)width else height
            val end=(candidate.normalizedEnd.coerceIn(0f,1f))*if(candidate.axis==CandidateAxis.HORIZONTAL)width else height
            if(candidate.axis==CandidateAxis.HORIZONTAL)canvas.drawLine(start,startPosition,end,endPosition,candidatePaint)
            else canvas.drawLine(startPosition,start,endPosition,end,candidatePaint)
        }
        fun drawEvidence(candidates: List<com.formsnap.app.processing.LineCandidateDiagnostic>) {
            candidates.filter { it.disposition==CandidateDisposition.SELECTED }.forEach(::draw)
            candidates.filter { it.disposition==CandidateDisposition.MERGED }.sortedByDescending { it.score }.take(80).forEach(::draw)
            candidates.filter { it.disposition==CandidateDisposition.REJECTED_HIGH_SCORE }.sortedByDescending { it.score }.take(48).forEach(::draw)
            candidates.filter { it.disposition==CandidateDisposition.REJECTED_LOW_SCORE }.sortedByDescending { it.score }.take(24).forEach(::draw)
        }
        drawEvidence(diagnostics.horizontalCandidates)
        drawEvidence(diagnostics.verticalCandidates)
        val evidencePaint=paint(Color.MAGENTA,220,(width/300f).coerceAtLeast(4f))
        val possiblePaint=paint(Color.rgb(255,150,0),150,(width/420f).coerceAtLeast(3f))
        when(diagnostics.clippingEvidence) {
            ClippingEvidence.LIKELY_CLIPPED_LEFT -> canvas.drawLine(2f,0f,2f,height.toFloat(),evidencePaint)
            ClippingEvidence.LIKELY_CLIPPED_RIGHT -> canvas.drawLine(width-2f,0f,width-2f,height.toFloat(),evidencePaint)
            ClippingEvidence.LIKELY_CLIPPED_BOTH -> { canvas.drawLine(2f,0f,2f,height.toFloat(),evidencePaint); canvas.drawLine(width-2f,0f,width-2f,height.toFloat(),evidencePaint) }
            ClippingEvidence.POSSIBLE_CLIPPING -> canvas.drawRect(2f,2f,width-2f,height-2f,possiblePaint)
            ClippingEvidence.NO_CLIPPING_EVIDENCE -> Unit
        }
    }

    private fun drawCell(canvas: Canvas, grid: com.formsnap.app.processing.TableGrid, cell: GridCell, paint: Paint, width: Int, height: Int) {
        val corners = grid.corners(cell)
        val path = Path().apply {
            moveTo(corners[0].x * width, corners[0].y * height)
            corners.drop(1).forEach { lineTo(it.x * width, it.y * height) }
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSegment(canvas: Canvas, from: com.formsnap.app.processing.TablePoint, to: com.formsnap.app.processing.TablePoint, paint: Paint, width: Int, height: Int) {
        canvas.drawLine(from.x * width, from.y * height, to.x * width, to.y * height, paint)
    }
}
