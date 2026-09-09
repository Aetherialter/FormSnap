package com.formsnap.app.domain

import com.formsnap.app.domain.model.RecognitionReliability
import com.formsnap.app.domain.model.SourceRegion
import com.formsnap.app.processing.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic pixels test structure recovery, not the ML model's real-world accuracy. */
class GridRecognitionTest {
    private val width = 600
    private val height = 500
    private val xs = listOf(40, 170, 300, 430, 560)
    private val ys = listOf(50, 130, 210, 290, 370, 450)
    private fun pixels(): ByteArray = ByteArray(width * height) { 255.toByte() }.also { image ->
        for (x in xs) for (y in ys.first()..ys.last()) image[y * width + x] = 0
        for (y in ys) for (x in xs.first()..xs.last()) image[y * width + x] = 0
    }
    private fun evidence(row: Int, col: Int, value: String, confidence: Float = 0.99f) = TextEvidence(value,
        SourceRegion((xs[col] + 15f) / width, (ys[row] + 20f) / height, (xs[col] + 65f) / width, (ys[row] + 45f) / height), confidence)

    @Test fun `actual raster grid recovers rows columns and source regions`() {
        val grid = GridDetector().detect(GrayImage(width, height, pixels()))
        assertEquals(xs, grid.xs)
        assertEquals(ys, grid.ys)
        assertEquals(4, grid.columns)
        assertEquals(5, grid.rows)
        assertTrue(grid.region(1, 2).left > 0.5f)
        assertTrue(grid.region(1, 2).bottom < 0.5f)
    }

    @Test fun `missing merged edge and cropped boundaries require structure review`() {
        val merged = pixels().also { data -> for (y in 51 until 130) data[y * width + 170] = 255.toByte() }
        val recovered = GridDetector().detect(GrayImage(width, height, merged))
        assertEquals(StructureOutcome.STRUCTURE_REVIEW_REQUIRED, recovered.outcome)
        assertTrue(recovered.merged.contains(GridCell(0, 0, 1, 2)))
        val original = pixels()
        val cut = ByteArray(521 * height) { index -> original[index / 521 * width + index % 521 + 40] }
        assertEquals(StructureOutcome.STRUCTURE_REVIEW_REQUIRED, GridDetector().detect(GrayImage(521, height, cut)).outcome)
    }

    @Test fun `blank or unruled pages fail instead of returning a fake table`() {
        assertThrows(PageRecognitionException::class.java) { GridDetector().detect(GrayImage(width, height, ByteArray(width * height) { 255.toByte() })) }
    }

    @Test fun `evidence maps to dynamic headers and cell regions while blank slots are skipped`() {
        val image = GrayImage(width, height, pixels())
        val grid = GridDetector().detect(image)
        val text = listOf("资产编号", "设备名称", "状态", "备注").mapIndexed { col, name -> evidence(0, col, name) } +
            listOf(evidence(1, 0, "001"), evidence(1, 1, "设备"), evidence(3, 0, "002"))
        val page = TableCandidateAssembler().assemble("page", image, grid, text)
        assertEquals(listOf("资产编号", "设备名称", "状态", "备注"), page.headers)
        assertEquals(listOf(1, 3), page.rows.map { it.originalRowIndex })
        assertEquals("", page.rows.first().cells[2].rawValue)
        assertEquals(grid.region(1, 0), page.rows.first().cells[0].region)
    }

    @Test fun `unreadable ink low confidence and cell boundary crossing cannot be auto accepted`() {
        val data = pixels()
        for (y in 155..170) for (x in 330..350) data[y * width + x] = 0
        val image = GrayImage(width, height, data)
        val grid = GridDetector().detect(image)
        val header = (0..3).map { evidence(0, it, "字段$it") }
        val page = TableCandidateAssembler().assemble("page", image, grid, header + evidence(1, 0, "S12", 0.4f))
        assertEquals(RecognitionReliability.LOW, page.rows[0].cells[0].reliability)
        assertEquals(RecognitionReliability.UNREADABLE, page.rows[0].cells[2].reliability)
        assertEquals("", page.rows[0].cells[2].rawValue)
        val cross = TextEvidence("跨列", SourceRegion(0.25f, 0.30f, 0.35f, 0.35f), 0.99f)
        assertThrows(StructureReviewException::class.java) { TableCandidateAssembler().assemble("page", image, grid, header + cross) }
    }

    @Test fun `text centered on grid stroke cannot disappear from candidate data`() {
        val image = GrayImage(width, height, pixels())
        val grid = GridDetector().detect(image)
        val header = (0..3).map { evidence(0, it, "字段$it") }
        val onStroke = TextEvidence("123", SourceRegion(165f / width, .30f, 175f / width, .35f), .99f)
        assertThrows(StructureReviewException::class.java) {
            TableCandidateAssembler().assemble("page", image, grid, header + evidence(1, 0, "001") + onStroke)
        }
    }
}
