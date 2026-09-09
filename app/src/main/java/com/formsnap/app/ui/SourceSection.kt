package com.formsnap.app.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formsnap.app.R
import com.formsnap.app.domain.model.SourceStatus
import com.formsnap.app.domain.repository.SourceImportFailure

@Composable
fun SourceSection(model: SourceViewModel, editable: Boolean, onOpen: ((String) -> Unit)? = null, onReprocess: ((String) -> Unit)? = null) {
    val state by model.sources.collectAsStateWithLifecycle()
    val operation by model.operation.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(SourceImagePicker()) { uris ->
        model.add(uris.map { it.toString() })
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refresh() }
    SourceSectionContent(
        state, operation, notice, editable,
        onAdd = {
            try {
                picker.launch(arrayOf("image/*"))
            } catch (_: ActivityNotFoundException) {
                model.pickerUnavailable()
            } catch (_: SecurityException) {
                model.pickerUnavailable()
            }
        },
        onRemove = model::remove,
        onRefresh = model::retryLoading,
        onOpen = onOpen,
        onReprocess = onReprocess,
    )
}

@Composable
internal fun SourceSectionContent(
    state: SourceListState,
    operation: SourceOperation,
    notice: SourceNotice?,
    editable: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: ((String) -> Unit)? = null,
    onReprocess: ((String) -> Unit)? = null,
) {
    var pendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = operation != SourceOperation.IDLE
    Text(stringResource(R.string.paper_forms), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    when (state) {
        SourceListState.Loading -> Text(stringResource(R.string.sources_loading))
        SourceListState.Error -> {
            Text(stringResource(R.string.sources_load_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.retry)) }
        }
        is SourceListState.Ready -> Text(
            stringResource(R.string.sources_count, state.pages.size),
            modifier = Modifier.testTag("sourceCount"),
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onAdd, enabled = editable && !busy && state is SourceListState.Ready, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.add_forms))
    }
    Text(stringResource(R.string.source_reference_hint), style = MaterialTheme.typography.bodySmall)
    if (busy) {
        Text(stringResource(when (operation) {
            SourceOperation.ADDING -> R.string.sources_adding
            SourceOperation.REMOVING -> R.string.sources_removing
            else -> R.string.sources_checking
        }), modifier = Modifier.padding(vertical = 12.dp))
    }
    notice?.let { SourceNoticeText(it) }
    if (state is SourceListState.Ready) {
        if (state.pages.isEmpty()) {
            Text(stringResource(R.string.no_sources), modifier = Modifier.padding(vertical = 24.dp))
        } else {
            TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.recheck_sources)) }
            state.pages.forEach { page ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.source_page, page.pageIndex + 1), style = MaterialTheme.typography.titleMedium)
                        Text(
                            page.displayName ?: stringResource(R.string.source_image),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(if (page.status == SourceStatus.AVAILABLE) R.string.source_available else R.string.source_unavailable),
                            color = if (page.status == SourceStatus.AVAILABLE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    val removeDescription = stringResource(R.string.remove_source_description, page.pageIndex + 1)
                    TextButton(
                        onClick = { pendingRemoval = page.id }, enabled = editable && !busy,
                        modifier = Modifier.semantics { contentDescription = removeDescription },
                    ) { Text(stringResource(R.string.remove)) }
                }
                if (page.status == SourceStatus.UNAVAILABLE) {
                    Text(stringResource(R.string.source_unavailable_hint), style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    onOpen?.let { TextButton(onClick = { it(page.id) }) { Text("查看原页面") } }
                    onReprocess?.let { TextButton(onClick = { it(page.id) }, enabled = editable && !busy && page.status == SourceStatus.AVAILABLE) { Text("重新整理此页") } }
                }
            }
        }
    }
    pendingRemoval?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_source_title)) },
            text = { Text(stringResource(R.string.remove_source_hint)) },
            confirmButton = {
                TextButton(onClick = { pendingRemoval = null; onRemove(id) }, enabled = editable && !busy, modifier = Modifier.testTag("confirmRemoveSource")) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SourceNoticeText(notice: SourceNotice) {
    Column(Modifier.padding(vertical = 12.dp)) {
        when (notice) {
            is SourceNotice.Imported -> {
                val result = notice.result
                Text(stringResource(R.string.sources_imported, result.added, result.duplicates, result.rejected.size))
                for (reason in result.rejected.map { it.reason }.distinct()) {
                    Text(stringResource(when (reason) {
                        SourceImportFailure.PERMISSION_NOT_RETAINED -> R.string.source_permission_failed
                        SourceImportFailure.UNSUPPORTED_SOURCE -> R.string.source_unsupported
                        SourceImportFailure.TIMEOUT -> R.string.source_timed_out
                        SourceImportFailure.UNREADABLE -> R.string.source_read_failed
                    }))
                }
                if (result.permissionCleanupPending) Text(stringResource(R.string.source_cleanup_pending))
            }
            else -> Text(stringResource(when (notice) {
                SourceNotice.Removed -> R.string.source_removed
                SourceNotice.CleanupPending -> R.string.source_removed_cleanup_pending
                SourceNotice.AddFailed -> R.string.sources_add_failed
                SourceNotice.RemoveFailed -> R.string.source_remove_failed
                SourceNotice.CheckFailed -> R.string.sources_check_failed
                SourceNotice.PickerUnavailable -> R.string.source_picker_unavailable
            }))
        }
    }
}
