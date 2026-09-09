package com.formsnap.app.domain

import com.formsnap.app.domain.model.*
import com.formsnap.app.export.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class XlsxWriterTest {
    private fun dataset() = StructuredDataset(TableSchema("schema", "task", listOf(FieldDefinition("id", "资产编号", FieldType.ID), FieldDefinition("n", "数量", FieldType.INTEGER))),
        listOf(TableRow("row", "task", "schema", listOf(Cell("id-cell", "row", "id", "raw", confirmedValue = "001234567890123456", reviewStatus = CellReviewStatus.MANUAL_CONFIRMED),
            Cell("n-cell", "row", "n", "S12", confirmedValue = "512", reviewStatus = CellReviewStatus.MANUAL_CONFIRMED)))))

    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val item = zip.nextEntry ?: break; put(item.name, zip.readBytes().toString(Charsets.UTF_8)); zip.closeEntry() }
        }
    }
    private fun write(data: StructuredDataset, reviews: List<ExportReviewRecord> = emptyList()) = ByteArrayOutputStream().use { out -> XlsxWriter().write(data, reviews, out); entries(out.toByteArray()) }

    @Test fun `OOXML uses dynamic headers final values and separate review history`() {
        val files = write(dataset(), listOf(ExportReviewRecord("1", "数量", "S12", "512", "INVALID_FORMAT", "EDIT")))
        assertEquals(6, files.size)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        files.values.forEach { factory.newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray())) }
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("资产编号"))
        assertTrue(sheet.contains("001234567890123456"))
        assertTrue(sheet.contains("<v>512</v>"))
        assertFalse(sheet.contains("S12"))
        assertTrue(files.getValue("xl/worksheets/sheet2.xml").contains("S12"))
    }

    @Test fun `text escaping and formula-looking input remain literal and excluded rows are omitted`() {
        val data = dataset()
        val row = data.rows.single()
        val changed = row.copy(cells = row.cells.map { if (it.fieldId == "id") it.copy(confirmedValue = "=1+1 <&> 中文") else it })
        val excluded = row.copy(id = "excluded", excluded = true, cells = row.cells.map { it.copy(id = "excluded-${it.id}", rowId = "excluded", confirmedValue = "EXCLUDED") })
        val sheet = write(data.copy(rows = listOf(changed, excluded))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("=1+1 &lt;&amp;&gt; 中文"))
        assertFalse(sheet.contains("<f>"))
        assertFalse(sheet.contains("EXCLUDED"))
    }

    @Test fun `unvalidated values and XML-illegal characters are rejected without raw fallback`() {
        val data = dataset()
        val row = data.rows.single()
        val unvalidated = row.copy(cells = row.cells.map { it.copy(confirmedValue = null, reviewStatus = CellReviewStatus.UNVALIDATED) })
        assertThrows(IllegalArgumentException::class.java) { write(data.copy(rows = listOf(unvalidated))) }
        val illegal = row.copy(cells = row.cells.map { it.copy(confirmedValue = "bad\u0001") })
        assertThrows(IllegalArgumentException::class.java) { write(data.copy(rows = listOf(illegal))) }
    }

    @Test fun `merged rows preserve field id ordering when headers are reordered`() {
        val data = dataset()
        val second = data.rows.single().let { row -> row.copy(id = "row2", cells = row.cells.map { it.copy(id = "${it.id}-2", rowId = "row2", confirmedValue = "2") }) }
        val sheet = write(data.copy(schema = data.schema.copy(fields = data.schema.fields.reversed()), rows = data.rows + second)).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.indexOf("数量") < sheet.indexOf("资产编号"))
        assertTrue(sheet.contains("<c r=\"A2\"><v>512</v></c>"))
        assertTrue(sheet.contains("<row r=\"3\">"))
    }
}
