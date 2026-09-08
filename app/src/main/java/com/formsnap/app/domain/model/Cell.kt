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
    val reviewStatus: CellReviewStatus = CellReviewStatus.NEEDS_REVIEW,
    val source: CellSource? = null,
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

enum class CellReviewStatus { AUTO_CONFIRMED, NEEDS_REVIEW, MANUAL_CONFIRMED, UNREADABLE }
