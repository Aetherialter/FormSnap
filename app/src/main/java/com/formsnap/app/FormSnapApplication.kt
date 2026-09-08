package com.formsnap.app

import android.app.Application
import com.formsnap.app.data.RoomTaskRepository
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.domain.repository.TaskRepository

class FormSnapApplication : Application() {
    private val database by lazy { FormSnapDatabase.open(this) }
    val taskRepository: TaskRepository by lazy { RoomTaskRepository(database.taskDao()) }
}
