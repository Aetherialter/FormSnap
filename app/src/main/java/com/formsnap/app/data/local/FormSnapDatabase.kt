package com.formsnap.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskEntity::class, SourceEntity::class, SchemaEntity::class, FieldEntity::class, RowEntity::class, CellEntity::class],
    version = 3, exportSchema = true,
)
abstract class FormSnapDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun sourceDao(): SourceDao
    abstract fun structuredDao(): StructuredDao

    companion object {
        val MIGRATION_2_3: Migration = StructuredMigration
        val ALL_MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
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
