package com.formsnap.app.domain.model

import java.time.Instant

/** URI text keeps Android dependencies outside the domain. Permission ownership belongs to data. */
data class SourceDocument(
    val id: String,
    val taskId: String,
    val sourceUri: String,
    val pageIndex: Int,
    val status: SourceStatus,
    val createdAt: Instant,
    val displayName: String? = null,
) {
    init {
        require(id.isNotBlank() && taskId.isNotBlank() && sourceUri.isNotBlank())
        require(pageIndex >= 0)
    }
}

enum class SourceStatus { AVAILABLE, UNAVAILABLE }

/** Coordinates refer to the original page, normalized to [0, 1], before any image transforms. */
data class SourceRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

data class CellSource(
    val documentId: String,
    val originalRowIndex: Int? = null,
    val originalColumnIndex: Int? = null,
    val region: SourceRegion? = null,
) {
    init {
        require(documentId.isNotBlank())
        require(originalRowIndex == null || originalRowIndex >= 0)
        require(originalColumnIndex == null || originalColumnIndex >= 0)
    }
}
