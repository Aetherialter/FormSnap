package com.formsnap.app.export

import com.formsnap.app.domain.model.*
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportReviewRecord(val row: String, val field: String, val rawValue: String?, val finalValue: String?, val issues: String, val action: String)

/** Small standards-based OOXML writer: no spreadsheet runtime, formulas, macros or image copies. */
class XlsxWriter {
    fun write(dataset: StructuredDataset, reviews: List<ExportReviewRecord>, output: OutputStream) {
        val rows = dataset.rows.filterNot { it.excluded }
        require(dataset.schema.configurationConfirmed && dataset.schema.fields.isNotEmpty() && rows.isNotEmpty())
        require(rows.all { row -> row.cells.all { it.confirmedValue != null && it.reviewStatus in setOf(CellReviewStatus.AUTO_CONFIRMED, CellReviewStatus.MANUAL_CONFIRMED) } })
        require(dataset.schema.fields.size <= 16384 && rows.size < 1_048_576)
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>""".trimIndent())
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""".trimIndent())
            entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="$SHEET_NS" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="最终数据" sheetId="1" r:id="rId1"/><sheet name="复核记录" sheetId="2" r:id="rId2"/></sheets></workbook>""".trimIndent())
            entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""".trimIndent())
            entry("xl/worksheets/sheet1.xml", sheet(dataset.schema.fields.map { it.name }, rows.map { row ->
                val values = row.cells.associateBy { it.fieldId }
                dataset.schema.fields.map { field ->
                    val value = values.getValue(field.id).confirmedValue!!
                    val numeric = field.type in setOf(FieldType.INTEGER, FieldType.DECIMAL) && value.length <= 15 &&
                        value.matches(Regex("[+-]?[0-9]+(?:\\.[0-9]+)?"))
                    SheetValue(value, numeric)
                }
            }))
            entry("xl/worksheets/sheet2.xml", sheet(listOf("记录", "字段", "原始值", "最终值", "问题类型", "处理方式"), reviews.map {
                listOf(it.row, it.field, it.rawValue.orEmpty(), it.finalValue.orEmpty(), it.issues, it.action).map { value -> SheetValue(value) }
            }))
        }
    }

    private data class SheetValue(val text: String, val number: Boolean = false)
    private fun sheet(headers: List<String>, rows: List<List<SheetValue>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"$SHEET_NS\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>")
        (listOf(headers.map { SheetValue(it) }) + rows).forEachIndexed { row, values ->
            append("<row r=\"${row + 1}\">")
            values.forEachIndexed { column, value ->
                require(value.text.length <= 32767) { "Excel cell text limit exceeded" }
                val reference = "${columnName(column)}${row + 1}"
                if (value.number) append("<c r=\"$reference\"><v>${escape(value.text.removePrefix("+"))}</v></c>")
                else append("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value.text)}</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        var result = ""
        while (value > 0) { value--; result = ('A' + value % 26) + result; value /= 26 }
        return result
    }
    private fun escape(value: String): String = buildString {
        value.codePoints().forEach { code ->
            require(code == 9 || code == 10 || code == 13 || code in 32..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF) {
                "Value contains a character not supported by XML"
            }
            when (code) { 38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;"); else -> appendCodePoint(code) }
        }
    }
    companion object { const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main" }
}
