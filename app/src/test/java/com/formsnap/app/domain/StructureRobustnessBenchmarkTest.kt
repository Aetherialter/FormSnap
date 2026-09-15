package com.formsnap.app.domain

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.formsnap.app.processing.GrayImage
import com.formsnap.app.processing.ClippingEvidence
import com.formsnap.app.processing.GridDetector
import com.formsnap.app.processing.StructureDetectorPolicy
import com.formsnap.app.processing.StructureOutcome
import com.formsnap.app.processing.TableGrid
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Anonymous structure benchmark for narrow columns, edge clipping, and input stability. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StructureRobustnessBenchmarkTest {
    @Test fun `narrow column rotation retains projected boundaries`() {
        val reports=mutableListOf<String>()
        val traces=mutableListOf<String>()
        val failures=mutableListOf<String>()
        for(columns in listOf(20,24,30,32,40)) for(style in listOf("clean","low-contrast","merged-texture")) {
            val xs=variableColumns(columns)
            val ys=(0..14).map { 90+it*42 }
            val base=renderGrid(xs,14,line=if(style=="low-contrast")142 else 92,
                background=if(style=="low-contrast")205 else 242,texture=style!="clean",
                headerRows=if(style=="merged-texture")3 else 1,
                mergedBoundaries=if(style=="merged-texture")setOf(2,7,13) else emptySet())
            for(angle in listOf(-2.0,-1.0,-.5,0.0,.5,1.0,2.0)) {
                val result=GridDetector().recover(rotate(base,angle))
                val d=result.diagnostics; val grid=result.grid
                val radians=Math.toRadians(angle); val c=cos(radians); val s=sin(radians)
                fun unrotate(p:com.formsnap.app.processing.TablePoint):Pair<Double,Double> {
                    val cx=(base.width-1)/2.0; val cy=(base.height-1)/2.0
                    val x=p.x*base.width-cx; val y=p.y*base.height-cy
                    return (c*x+s*y+cx) to (-s*x+c*y+cy)
                }
                val actualX=grid?.xs.orEmpty().map { x -> unrotate(grid!!.toImage(x.toFloat(),grid.height/2f)).first }
                val actualY=grid?.ys.orEmpty().map { y -> unrotate(grid!!.toImage(grid.width/2f,y.toFloat())).second }
                fun matched(expected:List<Int>,actual:List<Double>):Int {
                    val remaining=actual.toMutableList(); var count=0
                    for(target in expected) {
                        val closest=remaining.minByOrNull { abs(it-target) } ?: continue
                        if(abs(closest-target)<=4.0) { count++; remaining.remove(closest) }
                    }
                    return count
                }
                val recall=(matched(xs,actualX)+matched(ys,actualY)).toDouble()/(xs.size+ys.size)
                val columnError=abs((grid?.columns ?: 0)-columns); val rowError=abs((grid?.rows ?: 0)-14)
                val success=grid!=null && columnError==0 && rowError==0 && recall>=.98
                if(!success)failures+="$columns/$style/$angle: ${d.failureStage}, errors=$columnError/$rowError recall=$recall"
                reports+=listOf(columns,style,angle,d.horizontalCandidateLines,d.verticalCandidateLines,
                    d.horizontalMergedCandidateLines,d.verticalMergedCandidateLines,grid?.rows ?: 0,grid?.columns ?: 0,
                    recall,columnError,rowError,success,result.outcome,d.failureStage ?: "NONE",d.proposedQuad).joinToString("\t")
                d.geometryStages.forEach { stage ->
                    traces+=listOf(columns,style,angle,stage.stage,stage.horizontal.size,stage.vertical.size,stage.intersections,
                        stage.horizontal.joinToString(";") { "${it.normalizedPosition}:${it.normalizedStartPosition}:${it.normalizedEndPosition}" },
                        stage.vertical.joinToString(";") { "${it.normalizedPosition}:${it.normalizedStartPosition}:${it.normalizedEndPosition}" }).joinToString("\t")
                }
            }
        }
        File("build/reports/structure").mkdirs()
        File("build/reports/structure/rotation-benchmark.tsv").writeText("columns\tstyle\tangle\trawH\trawV\tmergedH\tmergedV\tselectedRows\tselectedColumns\tboundaryRecall\tcolumnCountError\trowCountError\tsuccess\tstructureStatus\tfailureStage\tquad\n"+reports.joinToString("\n"))
        File("build/reports/structure/rotation-stages.tsv").writeText("columns\tstyle\tangle\tstage\th\tv\tintersections\thPositionStartEnd\tvPositionStartEnd\n"+traces.joinToString("\n"))
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
    private data class Truth(val columns: Int, val rows: Int, val clipped: Boolean)
    private data class Case(
        val id: String,
        val category: String,
        val image: GrayImage,
        val truth: Truth,
        val headerDepth: Int = 1,
        val mergedHeaderBoundaries: Set<Int> = emptySet(),
    )

    @Test
    fun `narrow columns and clipped tables remain bounded while stable transforms are measured`() {
        val cases = listOf(
            Case("narrow-20-mixed", "NARROW_COLUMN", renderGrid(variableColumns(20), rows = 14, texture = true), Truth(20, 14, false)),
            Case("narrow-24-screen-noise", "NARROW_COLUMN", renderGrid(variableColumns(24), rows = 14, texture = true, screenNoise = true), Truth(24, 14, false)),
            Case("narrow-24-two-header", "NARROW_COLUMN", renderGrid(variableColumns(24), rows = 14, texture = true, headerRows = 2, mergedBoundaries = setOf(3, 11)), Truth(24, 14, false), headerDepth = 2, mergedHeaderBoundaries = setOf(3, 11)),
            Case("narrow-32-mixed", "NARROW_COLUMN", renderGrid(variableColumns(32), rows = 14, texture = true), Truth(32, 14, false)),
            Case(
                "narrow-32-three-header",
                "NARROW_COLUMN",
                renderGrid(variableColumns(32), rows = 14, texture = true, headerRows = 3, mergedBoundaries = setOf(2, 8, 15, 24)),
                Truth(32, 14, false),
                headerDepth = 3,
                mergedHeaderBoundaries = setOf(2, 8, 15, 24),
            ),
            Case("narrow-20-four-header", "NARROW_COLUMN", renderGrid(variableColumns(20), rows = 14, texture = true, headerRows = 4, mergedBoundaries = setOf(2, 7, 13)), Truth(20, 14, false), headerDepth = 4, mergedHeaderBoundaries = setOf(2, 7, 13)),
            Case("narrow-40-low-contrast", "NARROW_COLUMN", renderGrid(variableColumns(40), rows = 12, line = 142, background = 205, texture = true), Truth(40, 12, false)),
            Case("screen-texture-heavy", "SCREEN_TEXTURE_HEAVY", renderGrid(variableColumns(18), rows = 14, line = 118, background = 232, texture = true, screenNoise = true, uiBorder = true), Truth(18, 14, false)),
            Case("text-heavy-header", "TEXT_HEAVY_HEADER", renderGrid(variableColumns(12), rows = 16, texture = true, headerRows = 3, textHeavy = true, mergedBoundaries = setOf(2, 6, 9)), Truth(12, 16, false), headerDepth = 3, mergedHeaderBoundaries = setOf(2, 6, 9)),
            Case("ui-border-confusion", "UI_BORDER_CONFUSION", renderGrid(variableColumns(16), rows = 14, texture = true, screenNoise = true, uiBorder = true), Truth(16, 14, false)),
            Case("clip-left", "CLIPPED_TABLE", renderClipped(left = true, right = false), Truth(5, 12, true)),
            Case("clip-right", "CLIPPED_TABLE", renderClipped(left = false, right = true), Truth(5, 12, true)),
            Case("true-right-clipping", "TRUE_RIGHT_CLIPPING", renderClipped(left = false, right = true, partial = true), Truth(5, 12, true)),
            Case("clip-both", "CLIPPED_TABLE", renderClipped(left = true, right = true), Truth(5, 12, true)),
            Case("false-clipping-trap", "FALSE_CLIPPING_TRAP", renderEdgeTouchingComplete(), Truth(6, 12, false)),
            Case("detector-missing-edge", "DETECTOR_MISSING_EDGE", renderMissingEdge(), Truth(6, 12, false)),
        )
        val policy = StructureDetectorPolicy(32, 35, 205)
        val reportRows = mutableListOf<String>()
        for (testCase in cases) {
            val result = GridDetector(policy).recover(testCase.image)
            val grid = result.grid
            val detectedRows = grid?.rows ?: 0
            val detectedColumns = grid?.columns ?: 0
            val diagnostics=result.diagnostics
            val candidateLines=diagnostics.rawCandidateCount
            val likelyClipping=setOf(ClippingEvidence.LIKELY_CLIPPED_LEFT,ClippingEvidence.LIKELY_CLIPPED_RIGHT,ClippingEvidence.LIKELY_CLIPPED_BOTH)
            if (testCase.truth.clipped) {
                assertTrue("missing clipping evidence for "+testCase.id+" actual="+diagnostics.clippingEvidence+" reasons="+diagnostics.clippingEvidenceReasons.joinToString(";"), diagnostics.clippingEvidence in likelyClipping || diagnostics.clippingEvidence == ClippingEvidence.POSSIBLE_CLIPPING)
                assertFalse("unsafe auto acceptance for "+testCase.id, result.outcome == StructureOutcome.AUTO_ACCEPTED)
                assertTrue("fabricated columns for "+testCase.id, detectedColumns <= testCase.truth.columns + 1)
            } else if (testCase.category == "DETECTOR_MISSING_EDGE") {
                assertNotNullGrid(testCase.id, result.reasons, grid)
                assertFalse("unsafe auto acceptance for "+testCase.id, result.outcome == StructureOutcome.AUTO_ACCEPTED)
                assertTrue("missing edge was hidden by clipping evidence for "+testCase.id, diagnostics.clippingEvidence !in likelyClipping)
                assertTrue("fabricated columns for "+testCase.id, detectedColumns <= testCase.truth.columns)
            } else {
                assertNotNullGrid(testCase.id, result.reasons, grid)
                assertTrue("column loss for "+testCase.id, abs(detectedColumns - testCase.truth.columns) <= 1)
                assertTrue("row loss for "+testCase.id, abs(detectedRows - testCase.truth.rows) <= 1)
                assertTrue("false clipping signal for "+testCase.id, diagnostics.clippingEvidence == ClippingEvidence.NO_CLIPPING_EVIDENCE)
                if (testCase.mergedHeaderBoundaries.isNotEmpty()) {
                    assertTrue("merged header evidence missing for "+testCase.id, grid!!.merged.isNotEmpty())
                }
            }
            if (testCase.category == "TRUE_RIGHT_CLIPPING") {
                assertEquals("clipping side for "+testCase.id, ClippingEvidence.LIKELY_CLIPPED_RIGHT, diagnostics.clippingEvidence)
            }
            if (testCase.category == "FALSE_CLIPPING_TRAP") {
                assertEquals("false clipping for "+testCase.id, ClippingEvidence.NO_CLIPPING_EVIDENCE, diagnostics.clippingEvidence)
            }
            reportRows += listOf(
                testCase.category,
                testCase.id,
                testCase.truth.columns.toString(),
                detectedColumns.toString(),
                testCase.truth.rows.toString(),
                detectedRows.toString(),
                result.outcome.name,
                diagnostics.horizontalCandidateLines.toString(),
                diagnostics.verticalCandidateLines.toString(),
                diagnostics.horizontalMergedCandidateLines.toString(),
                diagnostics.verticalMergedCandidateLines.toString(),
                diagnostics.horizontalSelectedLines.toString(),
                diagnostics.verticalSelectedLines.toString(),
                diagnostics.rejectedLineCandidates.toString(),
                "%.3f".format(java.util.Locale.ROOT,diagnostics.candidateReductionRatio),
                if (testCase.truth.clipped) "EDGE_CLIPPED" else "FULLY_VISIBLE",
                diagnostics.clippingEvidence.name,
                testCase.headerDepth.toString(),
                testCase.mergedHeaderBoundaries.size.toString(),
                result.reasons.joinToString("|").replace("\t", " "),
            ).joinToString("\t")
        }

        val stabilityRows = listOf(cases[0], cases[1], cases[2], cases[3], cases[4], cases[5], cases[6]).flatMap { testCase ->
            stabilityReport(testCase, policy)
        }
        File("build/reports/structure").mkdirs()
        File("build/reports/structure/robustness-benchmark.tsv").writeText(
            buildString {
                appendLine("category\tid\texpectedColumns\tdetectedColumns\texpectedRows\tdetectedRows\toutcome\trawH\trawV\tmergedH\tmergedV\tselectedH\tselectedV\trejected\treduction\tboundaryMode\tclippingEvidence\theaderDepth\tmergedHeaderBoundaryCount\treasons")
                reportRows.forEach(::appendLine)
                appendLine()
                appendLine("stabilityCategory\tid\tvariant\texpectedColumns\tdetectedColumns\texpectedRows\tdetectedRows\tcolumnCountVariance\trowCountVariance\tstructureStability")
                stabilityRows.forEach(::appendLine)
            },
        )
    }

    private fun stabilityReport(testCase: Case, policy: StructureDetectorPolicy): List<String> {
        val variants = listOf(
            "base" to testCase.image,
            "brightness+8" to adjustBrightness(testCase.image, 8),
            "contrast-8" to adjustContrast(testCase.image, .92f),
            "jpeg-q70" to jpegRoundTrip(testCase.image, 70),
            "rotate+1" to rotate(testCase.image, 1.0),
            "crop-4" to crop(testCase.image, 4, 4, 2, 2),
        )
        val detections = variants.map { (_, image) -> GridDetector(policy).recover(image) }
        val columns = detections.map { it.grid?.columns ?: 0 }
        val rows = detections.map { it.grid?.rows ?: 0 }
        val stable = detections.count { detection ->
            detection.grid != null && abs(detection.grid.columns - testCase.truth.columns) <= 1 &&
                abs(detection.grid.rows - testCase.truth.rows) <= 1
        }.toDouble() / variants.size
        val columnVariance = variance(columns)
        val rowVariance = variance(rows)
        assertTrue("${testCase.id} structure stability too low: $stable", stable >= .8)
        return variants.indices.map { index ->
            val detection = detections[index]
            listOf(
                "STABILITY",
                testCase.id,
                variants[index].first,
                testCase.truth.columns.toString(),
                (detection.grid?.columns ?: 0).toString(),
                testCase.truth.rows.toString(),
                (detection.grid?.rows ?: 0).toString(),
                "${"%.3f".format(java.util.Locale.ROOT, columnVariance)}",
                "${"%.3f".format(java.util.Locale.ROOT, rowVariance)}",
                "${"%.3f".format(java.util.Locale.ROOT, stable)}",
            ).joinToString("\t")
        }
    }

    private fun variableColumns(count: Int): List<Int> {
        val widths = (0 until count).map { index ->
            when {
                index % 7 == 0 -> 23
                index % 5 == 0 -> 31
                index % 3 == 0 -> 47
                else -> 36
            }
        }
        return widths.runningFold(80) { total, width -> total + width }
    }

    private fun renderGrid(
        xs: List<Int>,
        rows: Int,
        width: Int = maxOf(xs.last() + 100, 1500),
        height: Int = 900,
        line: Int = 92,
        background: Int = 242,
        texture: Boolean = false,
        headerRows: Int = 1,
        mergedBoundaries: Set<Int> = emptySet(),
        screenNoise: Boolean = false,
        textHeavy: Boolean = false,
        uiBorder: Boolean = false,
        omitVerticalBoundaries: Set<Int> = emptySet(),
    ): GrayImage {
        val ys = (0..rows).map { 90 + it * 42 }
        return raster(xs, ys, width, height, line, background, texture, headerRows, mergedBoundaries, screenNoise, textHeavy, uiBorder, omitVerticalBoundaries)
    }

    private fun raster(
        xs: List<Int>,
        ys: List<Int>,
        width: Int,
        height: Int,
        line: Int,
        background: Int,
        texture: Boolean,
        headerRows: Int = 1,
        mergedBoundaries: Set<Int> = emptySet(),
        screenNoise: Boolean = false,
        textHeavy: Boolean = false,
        uiBorder: Boolean = false,
        omitVerticalBoundaries: Set<Int> = emptySet(),
    ): GrayImage {
        val pixels = ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val paper = if (texture) ((x * 7 + y * 11) % 9) else 0
            val vertical = xs.withIndex().any { (boundary, value) ->
                abs(x - value) <= 1 && y >= ys.first() && y <= ys.last() &&
                    (boundary !in omitVerticalBoundaries && (boundary !in mergedBoundaries || y > ys[headerRows.coerceIn(1, ys.lastIndex)]))
            }
            val horizontal = ys.any { abs(y - it) <= 1 } && x >= xs.first() && x <= xs.last()
            // Visible to a reader, but below the detector's local contrast threshold.
            val weakOuterEdge=omitVerticalBoundaries.any { abs(x-xs[it])<=1 } && y in ys.first()..ys.last()
            val uiLine = screenNoise && (y == 18 || y == height - 20 || x == 12)
            val uiTexture = screenNoise && x > width - 80 && y in 80..(height - 80) && y % 18 < 2
            val uiFrame = uiBorder && (x in 3..5 || x in (width - 6)..(width - 4) || y in 3..5 || y in (height - 6)..(height - 4))
            val headerText = textHeavy && y in ys.first() until ys[headerRows.coerceIn(1, ys.lastIndex)] && x in xs.first()..xs.last() && ((x / 5 + y / 7) % 17 < 2 || (x / 11 + y / 3) % 29 == 0)
            (if (vertical || horizontal) line else if(weakOuterEdge) background-20 else if (uiLine || uiTexture || uiFrame || headerText) 145 else (background - paper).coerceIn(0, 255)).toByte()
        }
        return GrayImage(width, height, pixels)
    }

    private fun renderClipped(left: Boolean, right: Boolean, partial: Boolean = false): GrayImage {
        val imageWidth = 1300
        val imageHeight = 900
        val tableWidth = 1200
        val tableHeight = 820
        val xs = (0..5).map { it * (tableWidth / 5) }
        val ys = (0..12).map { 70 + it * 60 }
        val grid = TableGrid(
            xs,
            ys,
            tableWidth,
            tableHeight,
            quad = com.formsnap.app.processing.TableQuad(
                com.formsnap.app.processing.TablePoint(if (left) -.20f else .08f, .12f),
                com.formsnap.app.processing.TablePoint(if (right) if (partial) 1.02f else 1.12f else .90f, .07f),
                com.formsnap.app.processing.TablePoint(if (right) if (partial) 1.12f else 1.24f else .94f, .91f),
                com.formsnap.app.processing.TablePoint(if (left) -.18f else .05f, .96f),
            ),
        )
        val pixels = ByteArray(imageWidth * imageHeight) { index ->
            val imagePoint = com.formsnap.app.processing.TablePoint(
                (index % imageWidth) / imageWidth.toFloat(),
                (index / imageWidth) / imageHeight.toFloat(),
            )
            val tablePoint = grid.fromImage(imagePoint)
            val x = tablePoint.x
            val y = tablePoint.y
            val vertical = xs.any { abs(x - it) <= 2 } && y >= ys.first() && y <= ys.last()
            val horizontal = ys.any { abs(y - it) <= 2 } && x >= xs.first() && x <= xs.last()
            (if (vertical || horizontal) 110 else 240).toByte()
        }
        return GrayImage(imageWidth, imageHeight, pixels)
    }

    private fun renderEdgeTouchingComplete(): GrayImage {
        val width=1300
        val height=900
        val xs=listOf(10,220,430,640,850,1060,1290)
        val ys=(0..12).map { 70+it*60 }
        return raster(xs,ys,width,height,line=110,background=240,texture=true)
    }

    private fun renderMissingEdge(): GrayImage {
        val width=1400
        val height=900
        val xs=(0..6).map { 80+it*170 }
        val ys=(0..12).map { 70+it*60 }
        return raster(xs,ys,width,height,line=100,background=240,texture=true,omitVerticalBoundaries=setOf(6))
    }

    private fun adjustBrightness(image: GrayImage, amount: Int): GrayImage = mapPixels(image) { (it + amount).coerceIn(0, 255) }

    private fun adjustContrast(image: GrayImage, factor: Float): GrayImage = mapPixels(image) {
        ((it - 128) * factor + 128).roundToInt().coerceIn(0, 255)
    }

    private fun mapPixels(image: GrayImage, transform: (Int) -> Int): GrayImage {
        val pixels = ByteArray(image.width * image.height) { index -> transform(image.value(index % image.width, index / image.width)).toByte() }
        return GrayImage(image.width, image.height, pixels)
    }

    private fun rotate(image: GrayImage, degrees: Double): GrayImage {
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        val cx = (image.width - 1) / 2.0
        val cy = (image.height - 1) / 2.0
        val pixels = ByteArray(image.width * image.height) { index ->
            val x = index % image.width - cx
            val y = index / image.width - cy
            val sourceX = (c * x + s * y + cx).roundToInt()
            val sourceY = (-s * x + c * y + cy).roundToInt()
            if (sourceX in 0 until image.width && sourceY in 0 until image.height) image.value(sourceX, sourceY).toByte() else 240.toByte()
        }
        return GrayImage(image.width, image.height, pixels)
    }

    private fun crop(image: GrayImage, left: Int, right: Int, top: Int, bottom: Int): GrayImage {
        val width = image.width - left - right
        val height = image.height - top - bottom
        val pixels = ByteArray(width * height) { index -> image.value(index % width + left, index / width + top).toByte() }
        return GrayImage(width, height, pixels)
    }

    private fun jpegRoundTrip(image: GrayImage, quality: Int): GrayImage {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(image.width * image.height) { index ->
            val value = image.value(index % image.width, index / image.width)
            -0x1000000 or (value shl 16) or (value shl 8) or value
        }
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val encoded = ByteArrayOutputStream().also { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) }.toByteArray()
        bitmap.recycle()
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
        val decodedPixels = IntArray(decoded.width * decoded.height)
        decoded.getPixels(decodedPixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        val result = GrayImage(decoded.width, decoded.height, ByteArray(decodedPixels.size) { index -> (decodedPixels[index] and 255).toByte() })
        decoded.recycle()
        return result
    }

    private fun variance(values: List<Int>): Double {
        val mean = values.average()
        return values.sumOf { (it - mean).pow(2) } / values.size
    }

    private fun assertNotNullGrid(id: String, reasons: List<String>, grid: TableGrid?) {
        assertTrue("$id did not detect a table: ${reasons.joinToString()}", grid != null)
    }
}
