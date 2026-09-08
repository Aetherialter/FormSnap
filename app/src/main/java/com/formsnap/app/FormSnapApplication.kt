package com.formsnap.app

import android.app.Application
import com.formsnap.app.data.RoomTaskRepository
import com.formsnap.app.data.RoomSourceRepository
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.data.source.AndroidSourceAccess
import com.formsnap.app.domain.repository.SourceRepository
import com.formsnap.app.domain.repository.TaskRepository

class FormSnapApplication : Application() {
    private val database by lazy { FormSnapDatabase.open(this) }
    val taskRepository: TaskRepository by lazy { RoomTaskRepository(database.taskDao()) }
    val sourceRepository: SourceRepository by lazy {
        RoomSourceRepository(database, AndroidSourceAccess(contentResolver))
    }
}
