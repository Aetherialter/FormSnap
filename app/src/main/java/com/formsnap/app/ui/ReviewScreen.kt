package com.formsnap.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.domain.model.*
import com.formsnap.app.review.*
import com.formsnap.app.validation.*

@Composable
fun ReviewScreen(model: WorkflowViewModel, sources: List<SourceDocument>, images: SourceImageLoader,
    onBack: () -> Unit, onFields: () -> Unit, onSources: () -> Unit, onData: () -> Unit, onStructure: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val activity by model.activity.collectAsStateWithLifecycle()
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    var wholeRow by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var excludeRow by rememberSaveable { mutableStateOf<String?>(null) }
    Page("待确认", onBack) {
        WorkflowNotice(model)
        WorkflowLoadState(state, model) { ready ->
            val snapshot = ready.snapshot
            val pending = ReviewQueue.items(snapshot.issues)
            val total = snapshot.issues.groupBy { it.target to it.targetId }.size
            Text("已处理 ${total - pending.size} / $total 项 · 还有 ${pending.size} 项待确认", modifier = Modifier.testTag("reviewProgress"))
            val item = pending.firstOrNull { it.key == selectedKey } ?: pending.firstOrNull { group -> group.issues.any { it.status == IssueStatus.OPEN } } ?: pending.firstOrNull()
            val enabled = !busy && activity?.busy == false
            if (item == null) {
                Text("当前没有待确认项目。", modifier = Modifier.padding(vertical = 24.dp))
                Button(onClick = onData) { Text("查看最终数据") }
            } else {
                Spacer(Modifier.height(16.dp))
                if (item.issues.all { it.status == IssueStatus.UNCONFIRMABLE }) {
                    Text("此项仍无法确认，需要补充原纸信息后处理；当前不能标准导出。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { selectedKey = pending[(pending.indexOf(item) + 1) % pending.size].key }) { Text("查看下一项") }
                }
                item.issues.forEach { Text("• ${it.message}", modifier = Modifier.padding(vertical = 4.dp)) }
                val dataset = snapshot.dataset
                when (item.target) {
                    IssueTarget.SCHEMA -> Button(onClick = onFields) { Text("核对字段设置") }
                    IssueTarget.SOURCE -> {
                        val source = sources.singleOrNull { it.id == item.targetId }
                        Text(source?.let { "第 ${it.pageIndex + 1} 页 · ${it.displayName.orEmpty()}" } ?: "来源页信息暂不可用")
                        SourceImagePane(source, null, images, Modifier.height(220.dp))
                        TextButton(onClick = { openPage = item.targetId }) { Text("查看原始页面") }
                        if(item.issues.any { it.code==IssueCode.STRUCTURE_REVIEW_REQUIRED || it.code==IssueCode.SCHEMA_MISMATCH })
                            Button(onClick=onStructure) { Text("确认表格结构") }
                        Button(onClick = onSources) { Text("返回来源页面处理") }
                    }
                    IssueTarget.CELL -> {
                        val row = dataset?.rows?.singleOrNull { record -> record.cells.any { it.id == item.targetId } }
                        val cell = row?.cells?.singleOrNull { it.id == item.targetId }
                        if (row != null && cell != null) {
                            val field = dataset.schema.fields.single { it.id == cell.fieldId }
                            Text("记录 ${dataset.rows.indexOf(row) + 1} · ${field.name}", style = MaterialTheme.typography.titleLarge)
                            val current = ValidationEngine.value(cell)
                            var value by rememberSaveable(cell.id, item.revision, current) { mutableStateOf(current) }
                            OutlinedTextField(value, { value = it }, label = { Text("当前值") }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("reviewValue"))
                            SourceImagePane(sources.singleOrNull { it.id == cell.source?.documentId }, cell.source?.region, images, Modifier.height(180.dp))
                            fun decide(action: ReviewAction) {
                                selectedKey = null
                                model.decide(ReviewDecision(IssueTarget.CELL, cell.id, action, if (action == ReviewAction.EDIT) value else null, item.revision, current))
                            }
                            Button(onClick = { decide(if (value != current) ReviewAction.EDIT else ReviewAction.CONFIRM) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().testTag("confirmReview")) { Text(if (value != current) "保存修改并确认" else "确认当前值") }
                            Row {
                                TextButton(onClick = { decide(ReviewAction.KEEP) }, enabled = enabled) { Text("保持原内容") }
                                TextButton(onClick = { decide(ReviewAction.UNREADABLE) }, enabled = enabled) { Text("无法确认") }
                            }
                            Row {
                                TextButton(onClick = { wholeRow = row.id }) { Text("查看整行") }
                                TextButton(onClick = { openPage = cell.source?.documentId }) { Text("查看原始页面") }
                            }
                        } else Text("记录已变化，请重新进入任务。")
                    }
                    IssueTarget.ROW_GROUP -> {
                        Text("疑似重复", style = MaterialTheme.typography.titleLarge)
                        val rowIds = item.issues.flatMap { it.relatedRowIds }.toSet()
                        dataset?.rows?.filter { it.id in rowIds }?.forEach { row ->
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                            Text("记录 ${dataset.rows.indexOf(row) + 1}", style = MaterialTheme.typography.titleMedium)
                            RowValues(row, dataset.schema)
                            Row {
                                TextButton(onClick = { openPage = row.sourceDocumentId }) { Text("查看原纸") }
                                TextButton(onClick = { excludeRow = row.id }, enabled = enabled) { Text("排除此记录") }
                            }
                        }
                        Button(onClick = { model.decide(ReviewDecision(IssueTarget.ROW_GROUP, item.targetId, ReviewAction.KEEP_RECORDS, expectedRevision = item.revision)) }, enabled = enabled) { Text("保留这些记录") }
                        TextButton(onClick = { model.decide(ReviewDecision(IssueTarget.ROW_GROUP, item.targetId, ReviewAction.DISTINCT_RECORDS, expectedRevision = item.revision)) }, enabled = enabled) { Text("视为不同业务记录") }
                    }
                }
                excludeRow?.let { rowId -> AlertDialog(onDismissRequest = { excludeRow = null }, title = { Text("排除误添加记录？") },
                    text = { Text("该记录将不进入最终数据，来源和处理证据仍保留。不会删除原始图片。") },
                    confirmButton = { TextButton(onClick = {
                        excludeRow = null
                        model.decide(ReviewDecision(IssueTarget.ROW_GROUP, item.targetId, ReviewAction.EXCLUDE_RECORD, rowId, item.revision))
                    }, enabled = enabled) { Text("排除") } }, dismissButton = { TextButton(onClick = { excludeRow = null }) { Text("取消") } }) }
            }
            wholeRow?.let { rowId -> snapshot.dataset?.rows?.singleOrNull { it.id == rowId }?.let { row ->
                AlertDialog(onDismissRequest = { wholeRow = null }, title = { Text("整行内容") }, text = { RowValues(row, snapshot.dataset.schema) },
                    confirmButton = { TextButton(onClick = { wholeRow = null }) { Text("关闭") } })
            } }
        }
    }
    openPage?.let { id -> SourceViewer(sources.singleOrNull { it.id == id }, images) { openPage = null } }
}

@Composable
internal fun RowValues(row: TableRow, schema: TableSchema) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        schema.fields.forEach { field ->
            val cell = row.cells.single { it.fieldId == field.id }
            Text("${field.name}：${ValidationEngine.value(cell).ifEmpty { "（空）" }}")
        }
    }
}
