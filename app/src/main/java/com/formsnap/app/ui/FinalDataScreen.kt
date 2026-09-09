package com.formsnap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.domain.model.*
import com.formsnap.app.review.*
import com.formsnap.app.validation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalDataScreen(model: WorkflowViewModel, sources: List<SourceDocument>, images: SourceImageLoader, canExport: Boolean,
    onBack: () -> Unit, onReview: () -> Unit, onFields: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val search by model.search.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val activity by model.activity.collectAsStateWithLifecycle()
    var selectedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCell by rememberSaveable { mutableStateOf<String?>(null) }
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("完整数据") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = { TextButton(onClick = onReview) { Text("待确认") } })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)) {
            WorkflowNotice(model)
            WorkflowLoadState(state, model) { ready ->
                val data = ready.snapshot.dataset
                if (data == null) Text("尚未生成记录，请先添加页面并开始整理。") else {
                    val activeRows = data.rows.filterNot { it.excluded }
                    val pending = ReviewQueue.items(ready.snapshot.issues)
                    Text("${activeRows.size} 条记录 · ${pending.size} 项待确认", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onFields) { Text("查看 / 调整字段设置") }
                    OutlinedTextField(search, model::search, label = { Text("搜索记录") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("searchRecords"))
                    Button(onClick = model::requestExport, enabled = canExport && !busy && activity?.busy == false,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("exportXlsx")) { Text("导出 XLSX") }
                    if (!canExport) Text("请先处理未完成页面和重要待确认项目。", style = MaterialTheme.typography.bodySmall)
                    val rows = activeRows.filter { row -> search.isBlank() || row.cells.any { ValidationEngine.value(it).contains(search.trim(), ignoreCase = true) } }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (rows.isEmpty()) item { Text(if (search.isBlank()) "暂无有效记录" else "没有匹配的记录") }
                        items(rows, key = { it.id }) { row ->
                            Column(Modifier.fillMaxWidth().clickable { selectedRow = row.id }.padding(vertical = 12.dp)) {
                                Text("记录 ${activeRows.indexOf(row) + 1}", style = MaterialTheme.typography.labelMedium)
                                row.cells.take(3).forEach { cell -> Text("${data.schema.fields.single { it.id == cell.fieldId }.name}：${ValidationEngine.value(cell).ifEmpty { "（空）" }}",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                Text("查看 / 修改", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            HorizontalDivider()
                        }
                    }
                    selectedRow?.let { rowId -> data.rows.singleOrNull { it.id == rowId }?.let { row ->
                        AlertDialog(onDismissRequest = { selectedRow = null }, title = { Text("记录内容") },
                            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                                row.cells.forEach { cell -> TextButton(onClick = { selectedRow = null; selectedCell = cell.id }, enabled = !busy && activity?.busy == false) {
                                    Text("${data.schema.fields.single { it.id == cell.fieldId }.name}：${ValidationEngine.value(cell).ifEmpty { "（空）" }}")
                                } }
                            } }, confirmButton = { TextButton(onClick = { selectedRow = null }) { Text("关闭") } },
                            dismissButton = { TextButton(onClick = { selectedRow = null; openPage = row.sourceDocumentId }) { Text("查看原始页面") } })
                    } }
                    selectedCell?.let { cellId -> data.rows.flatMap { it.cells }.singleOrNull { it.id == cellId }?.let { cell ->
                        val current = ValidationEngine.value(cell)
                        var edit by rememberSaveable(cell.id, current) { mutableStateOf(current) }
                        AlertDialog(onDismissRequest = { if (!busy) selectedCell = null }, title = { Text(data.schema.fields.single { it.id == cell.fieldId }.name) },
                            text = { Column {
                                OutlinedTextField(edit, { edit = it }, label = { Text("最终值") }, enabled = !busy, modifier = Modifier.testTag("editFinalValue"))
                                SourceImagePane(sources.singleOrNull { it.id == cell.source?.documentId }, cell.source?.region, images, Modifier.height(150.dp))
                                Text("修改会保留原始值，并重新检查相关问题。", style = MaterialTheme.typography.bodySmall)
                                WorkflowNotice(model)
                            } }, confirmButton = { TextButton(onClick = {
                                model.decide(ReviewDecision(IssueTarget.CELL, cell.id, if (edit == current) ReviewAction.CONFIRM else ReviewAction.EDIT, edit, expectedCellValue = current)) { selectedCell = null }
                            }, enabled = !busy && activity?.busy == false) { Text("保存并确认") } },
                            dismissButton = { TextButton(onClick = { selectedCell = null }, enabled = !busy) { Text("取消") } })
                    } }
                }
            }
        }
    }
    openPage?.let { id -> SourceViewer(sources.singleOrNull { it.id == id }, images) { openPage = null } }
}
