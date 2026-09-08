package com.formsnap.app.domain.model

import java.time.Instant

data class DigitizationTask(
    val id: String,
    val name: String,
    val status: TaskStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH)
        require(!updatedAt.isBefore(createdAt))
    }

    companion object {
        const val MAX_NAME_LENGTH = 120
    }
}

enum class TaskStatus {
    DRAFT, CAPTURING, PROCESSING, REVIEW_REQUIRED, READY_TO_EXPORT, EXPORTED, FAILED,
}
