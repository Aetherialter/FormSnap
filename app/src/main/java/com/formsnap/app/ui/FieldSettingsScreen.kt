package com.formsnap.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formsnap.app.domain.model.*

private data class FieldDraft(val id: String, val name: String, val type: FieldType, val required: Boolean,
    val pattern: String, val minimum: String, val maximum: String, val key: Boolean, val outliers: Boolean) {
    fun field() = FieldDefinition(id, name.trim(), type, FieldRules(required, pattern.takeIf { it.isNotBlank() },
        minimum.trim().takeIf { it.isNotEmpty() }, maximum.trim().takeIf { it.isNotEmpty() }, key, outliers))
}
private val DraftSaver = listSaver<List<FieldDraft>, String>(
    save = { fields -> fields.flatMap { listOf(it.id, it.name, it.type.name, it.required.toString(), it.pattern, it.minimum, it.maximum, it.key.toString(), it.outliers.toString()) } },
    restore = { flat -> flat.chunked(9).map { FieldDraft(it[0], it[1], FieldType.valueOf(it[2]), it[3].toBoolean(), it[4], it[5], it[6], it[7].toBoolean(), it[8].toBoolean()) } },
)

@Composable
fun FieldSettingsScreen(model: WorkflowViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val activity by model.activity.collectAsStateWithLifecycle()
    Page("字段设置", onBack) {
        Text("核对表头，再按材料设置检查规则。组合键中选中的字段将一起用于提示疑似重复。", style = MaterialTheme.typography.bodyMedium)
        WorkflowNotice(model)
        WorkflowLoadState(state, model) { ready ->
            val schema = ready.snapshot.dataset?.schema
            if (schema == null) Text("先添加页面并开始整理，再设置字段。") else {
                var drafts by rememberSaveable(schema.id, stateSaver = DraftSaver) { mutableStateOf(schema.fields.map {
                    FieldDraft(it.id, it.name, it.type, it.rules.required, it.rules.pattern.orEmpty(), it.rules.minimum.orEmpty(),
                        it.rules.maximum.orEmpty(), it.rules.duplicateKey, it.rules.detectColumnOutliers)
                }) }
                var error by rememberSaveable { mutableStateOf<String?>(null) }
                drafts.forEachIndexed { index, draft ->
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    FieldEditor(index, draft, !busy && activity?.busy == false) { changed ->
                        drafts = drafts.toMutableList().also { it[index] = changed }; error = null
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val parsed = runCatching { drafts.map { it.field() } }
                    if (parsed.isSuccess) model.fields(parsed.getOrThrow().map { field -> field.copy(headerPath = schema.fields.single { it.id == field.id }.headerPath) })
                    else error = "请检查字段名称、格式和数值范围。最小值不得大于最大值；格式不支持分组或分支。"
                }, enabled = !busy && activity?.busy == false, modifier = Modifier.fillMaxWidth().testTag("saveFields")) { Text("保存字段设置并检查") }
            }
        }
    }
}

@Composable
private fun FieldEditor(index: Int, draft: FieldDraft, enabled: Boolean, onChange: (FieldDraft) -> Unit) {
    Text("第 ${index + 1} 列", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) }, label = { Text("字段名称") }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) { Text("类型：${draft.type.displayName()}") }
        DropdownMenu(expanded, { expanded = false }) {
            FieldType.entries.forEach { type -> DropdownMenuItem(text = { Text(type.displayName()) }, onClick = {
                expanded = false; onChange(draft.copy(type = type,
                    minimum = if (type in setOf(FieldType.INTEGER, FieldType.DECIMAL)) draft.minimum else "",
                    maximum = if (type in setOf(FieldType.INTEGER, FieldType.DECIMAL)) draft.maximum else ""))
            }) }
        }
    }
    Toggle("必填", draft.required, enabled) { onChange(draft.copy(required = it)) }
    Toggle("用于组合重复键", draft.key, enabled) { onChange(draft.copy(key = it)) }
    Toggle("检查列内模式或长度异常", draft.outliers, enabled) { onChange(draft.copy(outliers = it)) }
    OutlinedTextField(draft.pattern, { onChange(draft.copy(pattern = it)) }, enabled = enabled,
        label = { Text("格式（可选）") }, supportingText = { Text("例如 [0-9]{8} 或 [A-Z]{2}[0-9]{4}；不支持分组和分支。") }, modifier = Modifier.fillMaxWidth())
    if (draft.type in setOf(FieldType.INTEGER, FieldType.DECIMAL)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft.minimum, { onChange(draft.copy(minimum = it)) }, enabled = enabled, label = { Text("最小值") }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(draft.maximum, { onChange(draft.copy(maximum = it)) }, enabled = enabled, label = { Text("最大值") }, modifier = Modifier.weight(1f), singleLine = true)
    }
    if (draft.type == FieldType.DATE) Text("日期采用 年-月-日，例如 2026-09-09。", style = MaterialTheme.typography.bodySmall)
    if (draft.type == FieldType.SIGNATURE_PRESENCE) Text("只检查是否填写签字，不判断签名身份。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Toggle(text: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(value, change, enabled = enabled); Text(text)
    }
}

internal fun FieldType.displayName(): String = when (this) {
    FieldType.TEXT -> "文本"; FieldType.INTEGER -> "整数"; FieldType.DECIMAL -> "数值 / 分数"
    FieldType.DATE -> "日期"; FieldType.BOOLEAN -> "是 / 否"; FieldType.ID -> "编号"; FieldType.PHONE -> "电话"
    FieldType.SIGNATURE_PRESENCE -> "签字是否填写"
}
