package com.formsnap.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus
import java.time.Instant

@Entity(tableName = "digitization_tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

internal fun TaskEntity.toDomain() = DigitizationTask(
    id = id,
    name = name,
    status = TaskStatus.valueOf(status),
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
)

internal fun DigitizationTask.toEntity() = TaskEntity(
    id = id,
    name = name,
    status = status.name,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
)
