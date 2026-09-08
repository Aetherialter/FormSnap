package com.formsnap.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TaskEntity::class], version = 1, exportSchema = true)
abstract class FormSnapDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        fun open(context: Context): FormSnapDatabase = Room.databaseBuilder(
            context.applicationContext,
            FormSnapDatabase::class.java,
            "formsnap.db",
        ).build()
    }
}
