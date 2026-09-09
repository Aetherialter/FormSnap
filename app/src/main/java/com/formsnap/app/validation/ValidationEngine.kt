package com.formsnap.app.validation

import com.formsnap.app.domain.model.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Pure quality analysis. No UI, OCR API, database access or business approval. */
class ValidationEngine {
    fun validate(dataset: StructuredDataset, pageProblems: List<PageProblem> = emptyList()): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (!dataset.schema.configurationConfirmed) {
            issues += ValidationIssue(fingerprint(dataset.schema.id, "configuration"), dataset.schema.taskId,
                IssueScope.SCHEMA, IssueTarget.SCHEMA, dataset.schema.id, IssueCode.STRUCTURE_WARNING,
                fingerprint(*dataset.schema.fields.map { it.name }.toTypedArray()),
                "请核对表头，并按材料设置字段类型、必填项、格式、范围及重复键。保存字段设置后继续检查。")
        }
        val fields = dataset.schema.fields.associateBy { it.id }
        val rows = dataset.rows.filterNot { it.excluded }
        val normalized = rows.flatMap { row -> row.cells.map { cell ->
            cell.copy(normalizedValue = normalize(cell.rawValue))
        } }
        fun issue(cell: Cell, code: IssueCode, message: String, scope: IssueScope = IssueScope.CELL, context: String = "") {
            val field = fields.getValue(cell.fieldId)
            val id = fingerprint(cell.id, code.name)
            issues += ValidationIssue(id, dataset.schema.taskId, scope, IssueTarget.CELL, cell.id, code,
                fingerprint(value(cell), field.type.name, field.rules.toString(), context), message)
        }
        for (cell in normalized) {
            val field = fields.getValue(cell.fieldId)
            val value = value(cell)
            val human = cell.reviewStatus == CellReviewStatus.MANUAL_CONFIRMED
            if (cell.reviewStatus == CellReviewStatus.UNREADABLE || (!human && cell.reliability == RecognitionReliability.UNREADABLE)) {
                issue(cell, IssueCode.UNREADABLE, "原纸内容无法可靠辨认，请查看原件后处理。")
            } else if (!human && cell.reliability != RecognitionReliability.HIGH) {
                issue(cell, IssueCode.LOW_RELIABILITY, "当前内容缺少足够可靠的读取依据，请核对原纸。")
            }
            if (value.isEmpty()) {
                if (field.rules.required) issue(cell, IssueCode.MISSING_VALUE, "此字段配置为必填，但当前为空，请检查。")
                continue
            }
            val number = value.toBigDecimalOrNull()
            val validType = when (field.type) {
                FieldType.TEXT -> true
                FieldType.INTEGER -> value.matches(Regex("[+-]?[0-9]+"))
                FieldType.DECIMAL -> value.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"))
                FieldType.ID -> value.matches(Regex("[A-Za-z0-9_-]+"))
                FieldType.PHONE -> value.matches(Regex("\\+?[0-9][0-9 ()-]{5,23}[0-9]")) && value.count { it.isDigit() } in 7..15
                FieldType.DATE -> try { LocalDate.parse(value); true } catch (_: DateTimeParseException) { false }
                FieldType.BOOLEAN -> value.lowercase() in setOf("true", "false", "0", "1", "是", "否")
                FieldType.SIGNATURE_PRESENCE -> true
            }
            if (!validType) issue(cell, IssueCode.INVALID_FORMAT, "内容不符合当前字段的数据类型，请检查。")
            if (field.type == FieldType.SIGNATURE_PRESENCE && !human) {
                issue(cell, IssueCode.LOW_RELIABILITY, "请核对原纸是否填写签字；无需辨认签名文字。")
            }
            field.rules.pattern?.let {
                if (!FieldPattern(it).matches(value)) issue(cell, IssueCode.INVALID_FORMAT, "内容不符合配置的字段格式，请检查。", context = it)
            }
            if (field.rules.minimum != null || field.rules.maximum != null) {
                if (number == null) issue(cell, IssueCode.INVALID_FORMAT, "当前字段配置了数值范围，请检查是否为数字。")
                else if (field.rules.minimum?.toBigDecimal()?.let { number < it } == true ||
                    field.rules.maximum?.toBigDecimal()?.let { number > it } == true) {
                    issue(cell, IssueCode.OUT_OF_RANGE, "数值超出当前字段配置的合理范围，请检查。")
                }
            }
        }
        for (field in dataset.schema.fields.filter { it.rules.detectColumnOutliers || it.type == FieldType.ID }) {
            val column = normalized.filter { it.fieldId == field.id && value(it).isNotEmpty() }
            if (column.size < 4) continue
            val patterns = column.groupBy { characterPattern(value(it)) }
            val dominant = patterns.maxBy { it.value.size }
            if (dominant.value.size.toDouble() / column.size < 0.75) continue
            for (cell in column) if (characterPattern(value(cell)) != dominant.key) {
                issue(cell, IssueCode.COLUMN_PATTERN_MISMATCH, "内容长度或字符模式与本列多数记录不同，请检查。", IssueScope.COLUMN, dominant.key)
            }
        }
        val byId = normalized.associateBy { it.id }
        val keys = dataset.schema.fields.filter { it.rules.duplicateKey }.map { it.id }
        val allFields = dataset.schema.fields.map { it.id }
        val duplicateSets = if (keys.isEmpty()) listOf(allFields) else listOf(allFields, keys).distinct()
        val emitted = mutableSetOf<String>()
        for (selectedFields in duplicateSets) {
            val groups = rows.mapNotNull { row ->
                val values = selectedFields.map { field -> value(byId.getValue(row.cells.single { it.fieldId == field }.id)) }
                if (values.all { it.isEmpty() } || (selectedFields == keys && values.any { it.isEmpty() })) null
                else fingerprint(*values.toTypedArray()) to row
            }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            for ((key, duplicateRows) in groups) {
                val rowIds = duplicateRows.map { it.id }.sorted()
                val targetId = fingerprint(*rowIds.toTypedArray())
                if (!emitted.add(targetId)) continue
                issues += ValidationIssue(fingerprint(targetId, IssueCode.DUPLICATE_RECORD.name), dataset.schema.taskId,
                    IssueScope.DATASET, IssueTarget.ROW_GROUP, targetId, IssueCode.DUPLICATE_RECORD,
                    fingerprint(key, *selectedFields.toTypedArray()), "这些记录的全部字段或配置的组合键相同，请决定保留或排除误添加记录。", rowIds)
            }
        }
        for (problem in pageProblems) {
            issues += ValidationIssue(fingerprint(problem.sourceId, problem.code.name), dataset.schema.taskId,
                IssueScope.SCHEMA, IssueTarget.SOURCE, problem.sourceId, problem.code,
                fingerprint(problem.code.name, problem.message), problem.message)
        }
        val distinctIssues = issues.distinctBy { it.id }
        val blockedCells = distinctIssues.filter { it.target == IssueTarget.CELL }.map { it.targetId }.toSet()
        return ValidationResult(normalized.map { cell ->
            when (cell.reviewStatus) {
                CellReviewStatus.MANUAL_CONFIRMED, CellReviewStatus.UNREADABLE -> cell
                else -> if (cell.id in blockedCells) cell.copy(confirmedValue = null, reviewStatus = CellReviewStatus.NEEDS_REVIEW)
                    else cell.copy(confirmedValue = cell.normalizedValue.orEmpty(), reviewStatus = CellReviewStatus.AUTO_CONFIRMED)
            }
        }, distinctIssues)
    }

    companion object {
        /** Only trim edges and convert full-width ASCII. Never guess O/0, S/5, case or punctuation. */
        fun normalize(raw: String?): String? = raw?.map {
            when { it == '\u3000' -> ' '; it in '\uFF01'..'\uFF5E' -> (it.code - 0xFEE0).toChar(); else -> it }
        }?.joinToString("")?.trim()

        fun value(cell: Cell): String = if (cell.reviewStatus == CellReviewStatus.MANUAL_CONFIRMED) cell.confirmedValue.orEmpty()
            else cell.normalizedValue ?: normalize(cell.rawValue).orEmpty()

        private fun characterPattern(value: String): String = value.map {
            when { it in '0'..'9' -> 'D'; it in 'a'..'z' || it in 'A'..'Z' -> 'L'; it.code in 0x4E00..0x9FFF -> 'H'; else -> it }
        }.joinToString("")
    }
}
