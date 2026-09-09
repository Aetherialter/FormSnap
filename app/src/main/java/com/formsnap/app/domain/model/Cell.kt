package com.formsnap.app.domain.model

/**
 * null confirmedValue means no confirmed business value; an empty confirmed string is distinct.
 * There is deliberately no fallback from confirmedValue to rawValue or normalizedValue.
 * Future issues refer to this stable cell ID (one-to-many), not a single warning slot on Cell.
 */
data class Cell(
    val id: String,
    val rowId: String,
    val fieldId: String,
    val rawValue: String?,
    val normalizedValue: String? = null,
    val confirmedValue: String? = null,
    val reviewStatus: CellReviewStatus = CellReviewStatus.UNVALIDATED,
    val source: CellSource? = null,
    val reliability: RecognitionReliability = RecognitionReliability.UNKNOWN,
) {
    init {
        require(id.isNotBlank() && rowId.isNotBlank() && fieldId.isNotBlank())
        if (reviewStatus == CellReviewStatus.AUTO_CONFIRMED ||
            reviewStatus == CellReviewStatus.MANUAL_CONFIRMED
        ) {
            require(confirmedValue != null)
        }
    }
}

enum class CellReviewStatus { UNVALIDATED, AUTO_CONFIRMED, NEEDS_REVIEW, MANUAL_CONFIRMED, UNREADABLE }

/** Evidence supplied by an adapter, not a claim that the candidate is correct. */
enum class RecognitionReliability { UNKNOWN, HIGH, LOW, UNREADABLE }
