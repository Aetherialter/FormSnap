package com.formsnap.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskEntity::class, SourceEntity::class, SchemaEntity::class, FieldEntity::class, RowEntity::class, CellEntity::class,
        PageResultEntity::class, IssueEntity::class, ReviewDecisionEntity::class, PageStructureEntity::class],
    version = 6, exportSchema = true,
)
abstract class FormSnapDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun sourceDao(): SourceDao
    abstract fun structuredDao(): StructuredDao
    abstract fun qualityDao(): QualityDao
    abstract fun structureDao(): StructureDao

    companion object {
        val MIGRATION_2_3: Migration = StructuredMigration
        val MIGRATION_3_4: Migration = QualityMigration
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE table_schemas ADD COLUMN configurationConfirmed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE digitization_tasks SET status = 'REVIEW_REQUIRED' WHERE id IN (SELECT taskId FROM table_schemas)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5,6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE field_definitions ADD COLUMN headerPath TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE IF NOT EXISTS page_structures (sourceId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, payload TEXT NOT NULL, confirmed INTEGER NOT NULL, revision INTEGER NOT NULL, discardHumanWork INTEGER NOT NULL, FOREIGN KEY(sourceId,taskId) REFERENCES source_documents(id,taskId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_page_structures_sourceId_taskId ON page_structures(sourceId,taskId)")
                db.execSQL("CREATE INDEX index_page_structures_taskId ON page_structures(taskId)")
            }
        }
        val ALL_MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_documents (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        displayName TEXT,
                        FOREIGN KEY(taskId) REFERENCES digitization_tasks(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX index_source_documents_taskId_sourceUri ON source_documents(taskId, sourceUri)")
                db.execSQL("CREATE UNIQUE INDEX index_source_documents_taskId_pageIndex ON source_documents(taskId, pageIndex)")
            }
        }

        fun open(context: Context): FormSnapDatabase = Room.databaseBuilder(
            context.applicationContext,
            FormSnapDatabase::class.java,
            "formsnap.db",
        ).addMigrations(*ALL_MIGRATIONS).build()
    }
}
