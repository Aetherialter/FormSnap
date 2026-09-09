package com.formsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.formsnap.app.ui.FormSnapApp
import com.formsnap.app.ui.TaskViewModel
import com.formsnap.app.ui.theme.FormSnapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FormSnapTheme {
                val model: TaskViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        TaskViewModel(
                            (application as FormSnapApplication).taskRepository,
                            createSavedStateHandle(),
                        )
                    }
                })
                val app = application as FormSnapApplication
                FormSnapApp(model, app.sourceRepository, app.qualityRepository, app.processingScheduler, app.imageLoader, app.exporter, app.structureRepository)
            }
        }
    }
}
