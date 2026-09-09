package com.formsnap.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object StructuredMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX index_source_documents_id_taskId ON source_documents(id, taskId)")
        db.execSQL("""
            CREATE TABLE table_schemas (
                id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL,
                FOREIGN KEY(taskId) REFERENCES digitization_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_table_schemas_taskId ON table_schemas(taskId)")
        db.execSQL("CREATE UNIQUE INDEX index_table_schemas_id_taskId ON table_schemas(id, taskId)")
        db.execSQL("""
            CREATE TABLE field_definitions (
                id TEXT NOT NULL PRIMARY KEY, schemaId TEXT NOT NULL, taskId TEXT NOT NULL,
                position INTEGER NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
                required INTEGER NOT NULL, pattern TEXT, minimum TEXT, maximum TEXT,
                duplicateKey INTEGER NOT NULL, detectColumnOutliers INTEGER NOT NULL,
                sourceColumnIndex INTEGER NOT NULL, sourceHeader TEXT NOT NULL,
                FOREIGN KEY(schemaId, taskId) REFERENCES table_schemas(id, taskId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_field_definitions_schemaId_taskId ON field_definitions(schemaId, taskId)")
        db.execSQL("CREATE UNIQUE INDEX index_field_definitions_schemaId_position ON field_definitions(schemaId, position)")
        db.execSQL("CREATE UNIQUE INDEX index_field_definitions_id_schemaId_taskId ON field_definitions(id, schemaId, taskId)")
        db.execSQL("""
            CREATE TABLE table_rows (
                id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, schemaId TEXT NOT NULL,
                sourceDocumentId TEXT NOT NULL, originalRowIndex INTEGER NOT NULL, excluded INTEGER NOT NULL,
                FOREIGN KEY(schemaId, taskId) REFERENCES table_schemas(id, taskId) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(sourceDocumentId, taskId) REFERENCES source_documents(id, taskId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_table_rows_schemaId_taskId ON table_rows(schemaId, taskId)")
        db.execSQL("CREATE INDEX index_table_rows_sourceDocumentId_taskId ON table_rows(sourceDocumentId, taskId)")
        db.execSQL("CREATE UNIQUE INDEX index_table_rows_sourceDocumentId_originalRowIndex ON table_rows(sourceDocumentId, originalRowIndex)")
        db.execSQL("CREATE UNIQUE INDEX index_table_rows_id_schemaId_taskId_sourceDocumentId ON table_rows(id, schemaId, taskId, sourceDocumentId)")
        db.execSQL("""
            CREATE TABLE cells (
                id TEXT NOT NULL PRIMARY KEY, rowId TEXT NOT NULL, fieldId TEXT NOT NULL,
                schemaId TEXT NOT NULL, taskId TEXT NOT NULL, sourceDocumentId TEXT NOT NULL,
                originalColumnIndex INTEGER NOT NULL, rawValue TEXT, normalizedValue TEXT, confirmedValue TEXT,
                reviewStatus TEXT NOT NULL, reliability TEXT NOT NULL,
                regionLeft REAL NOT NULL, regionTop REAL NOT NULL, regionRight REAL NOT NULL, regionBottom REAL NOT NULL,
                FOREIGN KEY(rowId, schemaId, taskId, sourceDocumentId) REFERENCES table_rows(id, schemaId, taskId, sourceDocumentId) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(fieldId, schemaId, taskId) REFERENCES field_definitions(id, schemaId, taskId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_cells_rowId_schemaId_taskId_sourceDocumentId ON cells(rowId, schemaId, taskId, sourceDocumentId)")
        db.execSQL("CREATE INDEX index_cells_fieldId_schemaId_taskId ON cells(fieldId, schemaId, taskId)")
        db.execSQL("CREATE INDEX index_cells_taskId ON cells(taskId)")
        db.execSQL("CREATE UNIQUE INDEX index_cells_rowId_fieldId ON cells(rowId, fieldId)")
    }
}
