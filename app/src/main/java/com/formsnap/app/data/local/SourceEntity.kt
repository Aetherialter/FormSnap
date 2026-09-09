package com.formsnap.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.domain.model.SourceStatus
import java.time.Instant

@Entity(
    tableName = "source_documents",
    foreignKeys = [ForeignKey(
        entity = TaskEntity::class,
        parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["taskId", "sourceUri"], unique = true),
        Index(value = ["taskId", "pageIndex"], unique = true),
        Index(value = ["id", "taskId"], unique = true),
    ],
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val sourceUri: String,
    val pageIndex: Int,
    val status: String,
    val createdAtEpochMillis: Long,
    val displayName: String?,
)

internal fun SourceEntity.toDomain() = SourceDocument(
    id, taskId, sourceUri, pageIndex, SourceStatus.valueOf(status),
    Instant.ofEpochMilli(createdAtEpochMillis), displayName,
)
