package com.formsnap.app

import android.app.Application
import com.formsnap.app.data.RoomTaskRepository
import com.formsnap.app.data.RoomSourceRepository
import com.formsnap.app.data.RoomQualityRepository
import com.formsnap.app.data.RoomStructuredRepository
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.data.source.AndroidSourceAccess
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.processing.AndroidPageRecognizer
import com.formsnap.app.processing.ProcessingRunner
import com.formsnap.app.processing.ProcessingScheduler
import com.formsnap.app.domain.repository.SourceRepository
import com.formsnap.app.domain.repository.TaskRepository

class FormSnapApplication : Application() {
    private val database by lazy { FormSnapDatabase.open(this) }
    val taskRepository: TaskRepository by lazy { RoomTaskRepository(database.taskDao()) }
    val sourceRepository: SourceRepository by lazy {
        RoomSourceRepository(database, AndroidSourceAccess(contentResolver))
    }
    val structuredRepository by lazy { RoomStructuredRepository(database) }
    val qualityRepository by lazy { RoomQualityRepository(database) }
    val imageLoader by lazy { SourceImageLoader(contentResolver) }
    val processingRunner by lazy { ProcessingRunner(database, AndroidPageRecognizer(imageLoader)) }
    val processingScheduler by lazy { ProcessingScheduler(this) }
}
