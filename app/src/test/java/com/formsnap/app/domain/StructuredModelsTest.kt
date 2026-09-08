package com.formsnap.app.domain

import com.formsnap.app.domain.model.Cell
import com.formsnap.app.domain.model.CellReviewStatus
import com.formsnap.app.domain.model.CellSource
import com.formsnap.app.domain.model.FieldDefinition
import com.formsnap.app.domain.model.SourceRegion
import com.formsnap.app.domain.model.TableRow
import com.formsnap.app.domain.model.TableSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StructuredModelsTest {
    @Test
    fun `confirmation preserves recognition and normalization`() {
        val candidate = Cell("cell", "row", "field", rawValue = " S12 ", normalizedValue = "S12")
        val confirmed = candidate.copy(confirmedValue = "512", reviewStatus = CellReviewStatus.MANUAL_CONFIRMED)
        assertEquals(" S12 ", confirmed.rawValue)
        assertEquals("S12", confirmed.normalizedValue)
        assertEquals("512", confirmed.confirmedValue)
        assertNull(candidate.confirmedValue)
        assertEquals(CellReviewStatus.NEEDS_REVIEW, candidate.reviewStatus)
    }

    @Test
    fun `normalized candidate never silently becomes confirmed`() {
        val cell = Cell("cell", "row", "field", rawValue = " 123 ", normalizedValue = "123")
        assertNull(cell.confirmedValue)
    }

    @Test
    fun `confirmed empty value is distinct from absence of confirmation`() {
        val cell = Cell("cell", "row", "field", rawValue = null)
        assertEquals("", cell.copy(confirmedValue = "", reviewStatus = CellReviewStatus.MANUAL_CONFIRMED).confirmedValue)
        assertThrows(IllegalArgumentException::class.java) {
            cell.copy(reviewStatus = CellReviewStatus.AUTO_CONFIRMED)
        }
    }

    @Test
    fun `field associations survive header rename and reorder`() {
        val schema = TableSchema("schema", "task", listOf(FieldDefinition("a", "资产编号"), FieldDefinition("b", "位置")))
        val cell = Cell("cell", "row", "a", "001")
        val changed = schema.copy(fields = listOf(schema.fields[1], schema.fields[0].copy(name = "设备编号")))
        assertEquals("设备编号", changed.fields.single { it.id == cell.fieldId }.name)
        assertEquals("001", cell.rawValue)
    }

    @Test
    fun `duplicate field IDs are rejected but repeated display names are permitted`() {
        TableSchema("schema", "task", listOf(FieldDefinition("a", "备注"), FieldDefinition("b", "备注")))
        assertThrows(IllegalArgumentException::class.java) {
            TableSchema("schema", "task", listOf(FieldDefinition("a", "位置"), FieldDefinition("a", "备注")))
        }
    }

    @Test
    fun `row rejects foreign cells and multiple values for the same field`() {
        val cell = Cell("cell", "row", "field", "value")
        assertThrows(IllegalArgumentException::class.java) {
            TableRow("other-row", "task", "schema", listOf(cell))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TableRow("row", "task", "schema", listOf(cell, cell.copy(id = "other-cell")))
        }
    }

    @Test
    fun `confirmation retains original page and region`() {
        val source = CellSource("document", 4, 2, SourceRegion(0.1f, 0.2f, 0.4f, 0.5f))
        val cell = Cell("cell", "row", "field", "value", source = source)
        assertEquals(source, cell.copy(confirmedValue = "corrected").source)
    }

    @Test
    fun `source rejects invalid coordinates and negative indices`() {
        assertThrows(IllegalArgumentException::class.java) { SourceRegion(-0.1f, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegion(0.8f, 0f, 0.2f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegion(Float.NaN, 0f, 1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { CellSource("document", originalRowIndex = -1) }
    }
}
