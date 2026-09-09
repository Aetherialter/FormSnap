package com.formsnap.app.validation

import com.formsnap.app.domain.model.Cell
import com.formsnap.app.domain.model.StructuredDataset
import java.security.MessageDigest

enum class IssueCode {
    LOW_RELIABILITY, MISSING_VALUE, INVALID_FORMAT, OUT_OF_RANGE, PATTERN_OUTLIER,
    COLUMN_PATTERN_MISMATCH, DUPLICATE_RECORD, SCHEMA_MISMATCH, UNREADABLE, STRUCTURE_WARNING,
    SOURCE_UNAVAILABLE,
    STRUCTURE_REVIEW_REQUIRED,
}
enum class IssueScope { CELL, COLUMN, DATASET, SCHEMA }
enum class IssueTarget { CELL, ROW_GROUP, SOURCE, SCHEMA }
enum class IssueStatus { OPEN, RESOLVED, UNCONFIRMABLE }

data class ValidationIssue(
    val id: String,
    val taskId: String,
    val scope: IssueScope,
    val target: IssueTarget,
    val targetId: String,
    val code: IssueCode,
    val fingerprint: String,
    val message: String,
    val relatedRowIds: List<String> = emptyList(),
    val status: IssueStatus = IssueStatus.OPEN,
    val active: Boolean = true,
)

data class PageProblem(val sourceId: String, val code: IssueCode, val message: String)
data class ValidationResult(val cells: List<Cell>, val issues: List<ValidationIssue>)
data class QualitySnapshot(val dataset: StructuredDataset?, val issues: List<ValidationIssue>)

/** Length-prefixing avoids ambiguous compound keys (including arbitrary field values). */
internal fun fingerprint(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
