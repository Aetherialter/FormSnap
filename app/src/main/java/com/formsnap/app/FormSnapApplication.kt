package com.formsnap.app

import android.app.Application
import androidx.work.Configuration
import com.formsnap.app.data.RoomTaskRepository
import com.formsnap.app.data.RoomSourceRepository
import com.formsnap.app.data.RoomQualityRepository
import com.formsnap.app.data.RoomStructuredRepository
import com.formsnap.app.data.RoomStructureRepository
import com.formsnap.app.data.local.FormSnapDatabase
import com.formsnap.app.data.source.AndroidSourceAccess
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.processing.AndroidPageRecognizer
import com.formsnap.app.processing.ProcessingRunner
import com.formsnap.app.processing.ProcessingScheduler
import com.formsnap.app.export.AndroidXlsxExporter
import com.formsnap.app.domain.repository.SourceRepository
import com.formsnap.app.domain.repository.TaskRepository

class FormSnapApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()
    private val database by lazy { FormSnapDatabase.open(this) }
    val taskRepository: TaskRepository by lazy { RoomTaskRepository(database.taskDao()) }
    val sourceRepository: SourceRepository by lazy {
        RoomSourceRepository(database, AndroidSourceAccess(contentResolver))
    }
    val structuredRepository by lazy { RoomStructuredRepository(database) }
    val qualityRepository by lazy { RoomQualityRepository(database) }
    val imageLoader by lazy { SourceImageLoader(contentResolver) }
    private val pageRecognizer by lazy { AndroidPageRecognizer(imageLoader) }
    val structureRepository by lazy { RoomStructureRepository(database,pageRecognizer) }
    val processingRunner by lazy { ProcessingRunner(database, pageRecognizer) }
    val processingScheduler by lazy { ProcessingScheduler(this) }
    val exporter by lazy { AndroidXlsxExporter(database, contentResolver, sourceRepository) }
}
