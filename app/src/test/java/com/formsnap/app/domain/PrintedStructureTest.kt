package com.formsnap.app.domain

import com.formsnap.app.domain.model.SourceRegion
import com.formsnap.app.processing.*
import java.io.File
import android.graphics.BitmapFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.app.Application

/** Real font pixels exercise geometry. Annotation boxes isolate assignment from OCR accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrintedStructureTest {
    @Test fun `printed Chinese forms preserve topology despite text texture and merged headers`() {
        val reports=mutableListOf<String>()
        for(name in listOf("printed-regular","printed-36-column-header","printed-gaps-variable-width")) for(sample in listOf(1,2)) {
            val root=File("../real-world-table-fixtures")
            val truth=JSONObject(File(root,"$name.json").readText())
            val bitmap=BitmapFactory.decodeFile(File(root,"$name.png").absolutePath,BitmapFactory.Options().apply { inSampleSize=sample })!!
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val image=GrayImage(bitmap.width,bitmap.height,ByteArray(pixels.size) { n -> (pixels[n] and 255).toByte() })
            bitmap.recycle()
            val result=GridDetector().recover(image)
            assertNotNull("$name: ${result.reasons}",result.grid)
            val grid=result.grid!!
            assertEquals("$name rows: ${grid.ys}",truth.getInt("rows"),grid.rows)
            assertEquals("$name columns: ${grid.xs}",truth.getInt("columns"),grid.columns)
            val region=truth.getJSONArray("tableRegion")
            val corners=grid.corners(GridCell(0,0,grid.rows,grid.columns))
            assertEquals(region.getDouble(0)/truth.getInt("width"),corners[0].x.toDouble(),.01)
            assertEquals(region.getDouble(1)/truth.getInt("height"),corners[0].y.toDouble(),.01)
            assertEquals(region.getDouble(2)/truth.getInt("width"),corners[2].x.toDouble(),.01)
            assertEquals(region.getDouble(3)/truth.getInt("height"),corners[2].y.toDouble(),.01)
            val merged=truth.getJSONArray("merged").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { GridCell(it.getInt("row"),it.getInt("column"),it.getInt("rowSpan"),it.getInt("colSpan")) } } }
            assertEquals("$name merged",merged.toSet(),grid.merged.toSet())
            val words=truth.getJSONArray("textEvidence").let { array -> (0 until array.length()).map { i -> array.getJSONObject(i).let { word ->
                TextEvidence(word.getString("text"),SourceRegion(word.getDouble("left").toFloat(),word.getDouble("top").toFloat(),word.getDouble("right").toFloat(),word.getDouble("bottom").toFloat()),.99f)
            } } }
            val proposal=TableCandidateAssembler().propose(name,image,grid,words)
            assertEquals("$name header",truth.getInt("headerRows"),proposal.grid.headerEnd)
            assertEquals("$name assignment",0,proposal.assignmentErrors)
            assertEquals(words.size,proposal.logicalCells.count { !it.candidate.rawValue.isNullOrEmpty() })
            val annotations=truth.getJSONArray("textEvidence")
            for(i in 0 until annotations.length()) {
                val expected=annotations.getJSONObject(i)
                assertEquals("$name text ownership at $i",expected.getString("text"),proposal.logicalCells.single {
                    it.shape.row==expected.getInt("row") && it.shape.column==expected.getInt("column")
                }.candidate.rawValue)
            }
            assertEquals(truth.getString("expectedOutcome"),proposal.grid.outcome.name)
            reports+="$name/sample$sample\ttrue\t${truth.getInt("rows")}\t${grid.rows}\t${truth.getInt("columns")}\t${grid.columns}\t${if(merged.isEmpty())"n/a" else "1.0"}\ttrue\t${proposal.assignmentErrors}\t${proposal.grid.outcome}"
        }
        File("build/reports/structure").mkdirs()
        File("build/reports/structure/printed-metrics.tsv").writeText("case\ttableDetected\texpectedRows\tdetectedRows\texpectedColumns\tdetectedColumns\tmergedRegionRecall\theaderDepthCorrect\tcellAssignmentErrors\toutcome\n"+reports.joinToString("\n"))
    }
}
