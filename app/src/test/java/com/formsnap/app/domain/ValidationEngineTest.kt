package com.formsnap.app.domain

import com.formsnap.app.domain.model.*
import com.formsnap.app.review.ReviewQueue
import com.formsnap.app.validation.*
import org.junit.Assert.*
import org.junit.Test

class ValidationEngineTest {
    private val engine = ValidationEngine()
    private fun dataset(fields: List<FieldDefinition>, values: List<List<String?>>, reliability: RecognitionReliability = RecognitionReliability.HIGH): StructuredDataset {
        val schema = TableSchema("schema", "task", fields)
        return StructuredDataset(schema, values.mapIndexed { rowIndex, row ->
            TableRow("r$rowIndex", "task", "schema", row.mapIndexed { col, value ->
                Cell("c$rowIndex-$col", "r$rowIndex", fields[col].id, value, reliability = reliability)
            })
        })
    }

    @Test fun `unvalidated candidates are neither all reviewed nor all auto accepted`() {
        val field = FieldDefinition("n", "数量", FieldType.INTEGER)
        val input = dataset(listOf(field), listOf(listOf("12"), listOf("S12")))
        assertTrue(input.rows.all { it.cells.single().reviewStatus == CellReviewStatus.UNVALIDATED })
        val result = engine.validate(input)
        assertEquals(CellReviewStatus.AUTO_CONFIRMED, result.cells[0].reviewStatus)
        assertEquals("12", result.cells[0].confirmedValue)
        assertEquals(CellReviewStatus.NEEDS_REVIEW, result.cells[1].reviewStatus)
        assertNull(result.cells[1].confirmedValue)
    }

    @Test fun `required differs from optional blank and legitimate empty values can pass`() {
        val result = engine.validate(dataset(listOf(FieldDefinition("a", "必填", rules = FieldRules(required = true)), FieldDefinition("b", "备注")), listOf(listOf(null, ""))))
        assertEquals(listOf(IssueCode.MISSING_VALUE), result.issues.map { it.code })
        assertEquals("", result.cells[1].confirmedValue)
    }

    @Test fun `range and configured format produce generic issues without eligibility verdicts`() {
        val result = engine.validate(dataset(listOf(FieldDefinition("score", "分值", FieldType.DECIMAL, FieldRules(pattern = "[0-9]{3}", minimum = "0", maximum = "710"))),
            listOf(listOf("812"), listOf("S12"), listOf("512"))))
        assertTrue(result.issues.any { it.code == IssueCode.OUT_OF_RANGE && it.targetId == "c0-0" })
        assertTrue(result.issues.any { it.code == IssueCode.INVALID_FORMAT && it.targetId == "c1-0" })
        assertFalse(result.issues.any { it.targetId == "c2-0" })
        assertFalse(result.issues.any { "合格" in it.message || "审批" in it.message })
    }

    @Test fun `date phone integer decimal and signature types are generic`() {
        val fields = listOf(FieldDefinition("date", "日期", FieldType.DATE), FieldDefinition("phone", "电话", FieldType.PHONE),
            FieldDefinition("n", "整数", FieldType.INTEGER), FieldDefinition("d", "金额", FieldType.DECIMAL),
            FieldDefinition("s", "签署", FieldType.SIGNATURE_PRESENCE))
        val result = engine.validate(dataset(fields, listOf(listOf("2026-02-30", "123", "1.2", "-12.50", "张"))))
        assertEquals(3, result.issues.count { it.code == IssueCode.INVALID_FORMAT })
        assertTrue(result.issues.any { it.targetId == "c0-4" && it.code == IssueCode.LOW_RELIABILITY })
        assertEquals("-12.50", result.cells[3].confirmedValue)
    }

    @Test fun `normalization preserves raw and does not guess ambiguous characters`() {
        val result = engine.validate(dataset(listOf(FieldDefinition("a", "编号")), listOf(listOf("　２０２３O１　"))))
        assertEquals("　２０２３O１　", result.cells.single().rawValue)
        assertEquals("2023O1", result.cells.single().normalizedValue)
        assertEquals("2023O1", result.cells.single().confirmedValue)
    }

    @Test fun `unknown low and unreadable evidence never silently auto confirms`() {
        for (reliability in listOf(RecognitionReliability.UNKNOWN, RecognitionReliability.LOW, RecognitionReliability.UNREADABLE)) {
            val result = engine.validate(dataset(listOf(FieldDefinition("a", "值")), listOf(listOf("123")), reliability))
            assertNull(result.cells.single().confirmedValue)
            assertTrue(result.issues.any { it.code in setOf(IssueCode.LOW_RELIABILITY, IssueCode.UNREADABLE) })
        }
    }

    @Test fun `column pattern catches a plausible id with letter O and requires enough peers`() {
        val field = FieldDefinition("id", "编号", FieldType.ID)
        val result = engine.validate(dataset(listOf(field), listOf("20230101", "20230102", "2023O103", "20230104").map { listOf(it) }))
        assertEquals(listOf("c2-0"), result.issues.filter { it.scope == IssueScope.COLUMN }.map { it.targetId })
        assertFalse(engine.validate(dataset(listOf(field), listOf(listOf("A"), listOf("12")))).issues.any { it.scope == IssueScope.COLUMN })
    }

    @Test fun `multiple issues aggregate once per cell but remain individually inspectable`() {
        val field = FieldDefinition("n", "数量", FieldType.INTEGER, FieldRules(pattern = "[0-9]{2}"))
        val result = engine.validate(dataset(listOf(field), listOf(listOf("S1")), RecognitionReliability.LOW))
        assertEquals(setOf(IssueCode.LOW_RELIABILITY, IssueCode.INVALID_FORMAT), result.issues.map { it.code }.toSet())
        assertEquals(1, ReviewQueue.items(result.issues).size)
    }

    @Test fun `composite duplicate key never uses a single business field or ambiguous concatenation`() {
        val fields = listOf(FieldDefinition("id", "编号", rules = FieldRules(duplicateKey = true)),
            FieldDefinition("project", "项目", rules = FieldRules(duplicateKey = true)), FieldDefinition("note", "备注"))
        val result = engine.validate(dataset(fields, listOf(listOf("12", "3", "A"), listOf("1", "23", "B"), listOf("12", "3", "C"), listOf("12", "4", "D"))))
        val duplicate = result.issues.single { it.code == IssueCode.DUPLICATE_RECORD }
        assertEquals(listOf("r0", "r2"), duplicate.relatedRowIds)
        assertEquals(12, result.cells.size)
    }

    @Test fun `exact duplicate overlaps composite duplicate only once and does not delete rows`() {
        val fields = listOf(FieldDefinition("a", "资产", rules = FieldRules(duplicateKey = true)), FieldDefinition("b", "设备"))
        val input = dataset(fields, listOf(listOf("001", "示波器"), listOf("001", "示波器")))
        val result = engine.validate(input)
        assertEquals(1, result.issues.count { it.code == IssueCode.DUPLICATE_RECORD })
        assertEquals(2, input.rows.size)
    }

    @Test fun `schema and source problems remain separate from cell values`() {
        val result = engine.validate(dataset(listOf(FieldDefinition("a", "值")), listOf(listOf("正常"))),
            listOf(PageProblem("page-2", IssueCode.SCHEMA_MISMATCH, "页面结构不同")))
        assertEquals(IssueScope.SCHEMA, result.issues.single().scope)
        assertEquals(IssueTarget.SOURCE, result.issues.single().target)
    }

    @Test fun `bounded field patterns accept common configuration and reject backtracking constructs`() {
        assertTrue(FieldPattern("^[A-Z]{2}[0-9]{4}$").matches("AB0012"))
        assertTrue(FieldPattern("\\d{2,4}-?\\w+").matches("12-AB"))
        assertFalse(FieldPattern("[0-9]{8}").matches("2023O118"))
        assertFalse(FieldPattern(".*").matches("a".repeat(257)))
        assertThrows(IllegalArgumentException::class.java) { FieldPattern("(a+)+") }
        assertThrows(IllegalArgumentException::class.java) { FieldPattern("a|b") }
    }
}
