package com.formsnap.app.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formsnap.app.export.XlsxWriter

@Composable
fun WorkflowEffects(model: WorkflowViewModel, taskName: String) {
    val requested by model.exportRequested.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XlsxWriter.MIME)) { model.saveExport(it?.toString()) }
    LaunchedEffect(requested) {
        if (requested) {
            model.exportDialogOpened()
            try { launcher.launch(taskName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "表录数据" } + ".xlsx") }
            catch (_: ActivityNotFoundException) { model.exportPickerUnavailable() }
            catch (_: SecurityException) { model.exportPickerUnavailable() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
}

@Composable
fun WorkflowNotice(model: WorkflowViewModel) {
    val notice by model.notice.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    notice?.let { Text(it, modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary) }
}

@Composable
fun WorkflowLoadState(state: WorkflowState, model: WorkflowViewModel, content: @Composable (WorkflowState.Ready) -> Unit) {
    when (state) {
        WorkflowState.Loading -> Text("正在读取整理结果…")
        WorkflowState.Error -> {
            Text("整理结果暂时无法读取。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = model::retry) { Text("重试") }
        }
        is WorkflowState.Ready -> content(state)
    }
}
