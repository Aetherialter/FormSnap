package com.formsnap.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "table_schemas",
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["taskId"], unique = true), Index(value = ["id", "taskId"], unique = true)],
)
data class SchemaEntity(@PrimaryKey val id: String, val taskId: String)

@Entity(
    tableName = "field_definitions",
    foreignKeys = [ForeignKey(entity = SchemaEntity::class, parentColumns = ["id", "taskId"], childColumns = ["schemaId", "taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["schemaId", "taskId"]),
        Index(value = ["schemaId", "position"], unique = true),
        Index(value = ["id", "schemaId", "taskId"], unique = true),
    ],
)
data class FieldEntity(
    @PrimaryKey val id: String,
    val schemaId: String,
    val taskId: String,
    val position: Int,
    val name: String,
    val type: String,
    val required: Boolean,
    val pattern: String?,
    val minimum: String?,
    val maximum: String?,
    val duplicateKey: Boolean,
    val detectColumnOutliers: Boolean,
    // Stable original header identity lets display order change without changing source columns.
    val sourceColumnIndex: Int,
    val sourceHeader: String,
)

@Entity(
    tableName = "table_rows",
    foreignKeys = [
        ForeignKey(entity = SchemaEntity::class, parentColumns = ["id", "taskId"], childColumns = ["schemaId", "taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id", "taskId"], childColumns = ["sourceDocumentId", "taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["schemaId", "taskId"]), Index(value = ["sourceDocumentId", "taskId"]),
        Index(value = ["sourceDocumentId", "originalRowIndex"], unique = true),
        Index(value = ["id", "schemaId", "taskId", "sourceDocumentId"], unique = true),
    ],
)
data class RowEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val schemaId: String,
    val sourceDocumentId: String,
    val originalRowIndex: Int,
    val excluded: Boolean,
)

@Entity(
    tableName = "cells",
    foreignKeys = [
        ForeignKey(entity = RowEntity::class, parentColumns = ["id", "schemaId", "taskId", "sourceDocumentId"], childColumns = ["rowId", "schemaId", "taskId", "sourceDocumentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FieldEntity::class, parentColumns = ["id", "schemaId", "taskId"], childColumns = ["fieldId", "schemaId", "taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["rowId", "schemaId", "taskId", "sourceDocumentId"]),
        Index(value = ["fieldId", "schemaId", "taskId"]),
        Index(value = ["taskId"]),
        Index(value = ["rowId", "fieldId"], unique = true),
    ],
)
data class CellEntity(
    @PrimaryKey val id: String,
    val rowId: String,
    val fieldId: String,
    val schemaId: String,
    val taskId: String,
    val sourceDocumentId: String,
    val originalColumnIndex: Int,
    val rawValue: String?,
    val normalizedValue: String?,
    val confirmedValue: String?,
    val reviewStatus: String,
    val reliability: String,
    val regionLeft: Float,
    val regionTop: Float,
    val regionRight: Float,
    val regionBottom: Float,
)
