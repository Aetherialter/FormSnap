package com.formsnap.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "page_results", foreignKeys = [
    ForeignKey(entity = SourceEntity::class, parentColumns = ["id", "taskId"], childColumns = ["sourceId", "taskId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["sourceId", "taskId"]), Index(value = ["taskId"])])
data class PageResultEntity(
    @PrimaryKey val sourceId: String,
    val taskId: String,
    val state: String,
    val problemCode: String?,
    val message: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "validation_issues", foreignKeys = [
    ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["taskId"]), Index(value = ["target", "targetId"])])
data class IssueEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val scope: String,
    val target: String,
    val targetId: String,
    val code: String,
    val fingerprint: String,
    val message: String,
    val relatedRowIds: String,
    val status: String,
    val active: Boolean,
)

@Entity(tableName = "review_decisions", foreignKeys = [
    ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["taskId"])])
data class ReviewDecisionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val target: String,
    val targetId: String,
    val action: String,
    val rawValue: String?,
    val previousValue: String?,
    val finalValue: String?,
    val issueCodes: String,
    val createdAtEpochMillis: Long,
)
