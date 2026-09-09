package com.formsnap.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object QualityMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE page_results (
                sourceId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, state TEXT NOT NULL,
                problemCode TEXT, message TEXT, updatedAtEpochMillis INTEGER NOT NULL,
                FOREIGN KEY(sourceId, taskId) REFERENCES source_documents(id, taskId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_page_results_sourceId_taskId ON page_results(sourceId, taskId)")
        db.execSQL("CREATE INDEX index_page_results_taskId ON page_results(taskId)")
        db.execSQL("""
            CREATE TABLE validation_issues (
                id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, scope TEXT NOT NULL,
                target TEXT NOT NULL, targetId TEXT NOT NULL, code TEXT NOT NULL, fingerprint TEXT NOT NULL,
                message TEXT NOT NULL, relatedRowIds TEXT NOT NULL, status TEXT NOT NULL, active INTEGER NOT NULL,
                FOREIGN KEY(taskId) REFERENCES digitization_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_validation_issues_taskId ON validation_issues(taskId)")
        db.execSQL("CREATE INDEX index_validation_issues_target_targetId ON validation_issues(target, targetId)")
        db.execSQL("""
            CREATE TABLE review_decisions (
                id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, target TEXT NOT NULL, targetId TEXT NOT NULL,
                action TEXT NOT NULL, rawValue TEXT, previousValue TEXT, finalValue TEXT, issueCodes TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                FOREIGN KEY(taskId) REFERENCES digitization_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_review_decisions_taskId ON review_decisions(taskId)")
        // Preserve v3 candidates, but do not claim that they have already passed quality checks.
        db.execSQL("""
            INSERT INTO page_results(sourceId, taskId, state, problemCode, message, updatedAtEpochMillis)
            SELECT DISTINCT sourceDocumentId, taskId, 'SUCCEEDED', NULL, NULL, 0 FROM table_rows
        """.trimIndent())
    }
}
