package com.formsnap.app.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.formsnap.app.R
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus
import com.formsnap.app.domain.model.TaskCounts
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.domain.repository.SourceRepository
import com.formsnap.app.domain.repository.QualityRepository
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.processing.ProcessingGateway
import com.formsnap.app.processing.StructureRepository
import com.formsnap.app.export.TaskExporter
import com.formsnap.app.review.ReviewQueue
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.flow.catch

@Composable
fun FormSnapApp(model: TaskViewModel, sourceRepository: SourceRepository, qualityRepository: QualityRepository,
    processing: ProcessingGateway, images: SourceImageLoader, exporter: TaskExporter, structures: StructureRepository) {
    val navigation = rememberNavController()
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val name by model.taskName.collectAsStateWithLifecycle()
    val creation by model.creation.collectAsStateWithLifecycle()
    val createdId by model.createdTaskId.collectAsStateWithLifecycle()
    val counts by remember(qualityRepository) { qualityRepository.observeCounts().catch { emit(emptyList()) } }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(createdId) {
        createdId?.let { id ->
            if (navigation.currentDestination?.route == "create") {
                navigation.navigate("task/${Uri.encode(id)}") {
                    popUpTo("home")
                    launchSingleTop = true
                }
            }
            model.creationOpened()
        }
    }

    NavHost(navigation, startDestination = "home") {
        composable("home") {
            HomeScreen(
                tasks = tasks,
                onCreate = { navigation.navigate("create") { launchSingleTop = true } },
                onOpen = { navigation.navigate("task/${Uri.encode(it)}") },
                onRetry = model::retryLoading,
                counts = counts,
            )
        }
        composable("create") {
            CreateTaskScreen(
                name = name,
                creation = creation,
                onNameChange = model::changeName,
                onCreate = model::createTask,
                onBack = { navigation.popBackStack() },
            )
        }
        composable("task/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            val id = it.arguments?.getString("id").orEmpty()
            val sourceModel: SourceViewModel = viewModel(
                viewModelStoreOwner = it,
                factory = viewModelFactory { initializer { SourceViewModel(id, sourceRepository) } },
            )
            val detail by remember(model, id) { model.observeTask(id) }
                .collectAsStateWithLifecycle(initialValue = TaskDetailState.Loading)
            val workflow: WorkflowViewModel = viewModel(viewModelStoreOwner = it, factory = viewModelFactory {
                initializer { WorkflowViewModel(id, qualityRepository, sourceRepository, processing, exporter, createSavedStateHandle()) }
            })
            val pages by remember(id) { sourceRepository.observeSources(id).catch { emit(emptyList()) } }.collectAsStateWithLifecycle(initialValue = emptyList())
            WorkflowEffects(workflow, (detail as? TaskDetailState.Ready)?.task?.name ?: "表录数据")
            TaskDetailScreen(
                state = detail,
                sourceModel = sourceModel,
                onBack = { navigation.popBackStack() },
                onRetry = model::retryLoading,
                workflow = workflow,
                pages = pages,
                images = images,
                onReview = { navigation.navigate("task/$id/review") },
                onFields = { navigation.navigate("task/$id/fields") },
                onData = { navigation.navigate("task/$id/data") },
                onStructure = { navigation.navigate("task/$id/structure") },
            )
        }
        composable("task/{id}/structure", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id=entry.arguments?.getString("id").orEmpty()
            val structureModel: StructureViewModel = viewModel(viewModelStoreOwner=entry,factory=viewModelFactory {
                initializer { StructureViewModel(id,structures,processing,createSavedStateHandle()) }
            })
            val pages by remember(id) { sourceRepository.observeSources(id).catch { emit(emptyList()) } }.collectAsStateWithLifecycle(initialValue=emptyList())
            StructureScreen(structureModel,pages,images,{navigation.popBackStack()},{navigation.navigate("task/$id/fields")})
        }
        listOf("review", "fields", "data").forEach { screen ->
            composable("task/{id}/$screen", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val workflow: WorkflowViewModel = viewModel(viewModelStoreOwner = entry, factory = viewModelFactory {
                    initializer { WorkflowViewModel(id, qualityRepository, sourceRepository, processing, exporter, createSavedStateHandle()) }
                })
                val detail by remember(model, id) { model.observeTask(id) }.collectAsStateWithLifecycle(initialValue = TaskDetailState.Loading)
                val pages by remember(id) { sourceRepository.observeSources(id).catch { emit(emptyList()) } }.collectAsStateWithLifecycle(initialValue = emptyList())
                val task = (detail as? TaskDetailState.Ready)?.task
                WorkflowEffects(workflow, task?.name ?: "表录数据")
                when (screen) {
                    "fields" -> FieldSettingsScreen(workflow) { navigation.popBackStack() }
                    "review" -> ReviewScreen(workflow, pages, images, { navigation.popBackStack() },
                        { navigation.navigate("task/$id/fields") }, { navigation.popBackStack("task/{id}", false) }, { navigation.navigate("task/$id/data") },
                        { navigation.navigate("task/$id/structure") })
                    "data" -> FinalDataScreen(workflow, pages, images, task?.status in setOf(TaskStatus.READY_TO_EXPORT, TaskStatus.EXPORTED),
                        { navigation.popBackStack() }, { navigation.navigate("task/$id/review") }, { navigation.navigate("task/$id/fields") })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    tasks: TaskListState,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    counts: List<TaskCounts>,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.product_promise), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_task), modifier = Modifier.padding(vertical = 6.dp))
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(R.string.recent_tasks), style = MaterialTheme.typography.titleMedium)
            }
            when (tasks) {
                TaskListState.Loading -> item { LoadingTasks() }
                TaskListState.Error -> item { LoadError(onRetry) }
                is TaskListState.Ready -> {
                    if (tasks.tasks.isEmpty()) item {
                        Column(Modifier.padding(vertical = 24.dp)) {
                            Text(stringResource(R.string.no_tasks), style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(tasks.tasks, key = { it.id }) { task ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable(role = Role.Button) { onOpen(task.id) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(task.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(task.status.label()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            counts.singleOrNull { it.taskId == task.id }?.let { count ->
                                Text("${count.recordCount} 条记录 · " + if (task.status in setOf(TaskStatus.DRAFT, TaskStatus.CAPTURING, TaskStatus.PROCESSING)) "尚未完成检查" else "${count.reviewCount} 项待确认")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateTaskScreen(
    name: String,
    creation: CreationState,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val saving = creation == CreationState.Saving
    val tooLong = name.trim().length > DigitizationTask.MAX_NAME_LENGTH
    val canCreate = name.isNotBlank() && !tooLong && !saving
    Page(title = stringResource(R.string.new_task), onBack = onBack) {
        Text(stringResource(R.string.task_name_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth().testTag("taskName"),
            label = { Text(stringResource(R.string.task_name)) },
            supportingText = {
                Text(
                    if (tooLong) stringResource(R.string.name_too_long, DigitizationTask.MAX_NAME_LENGTH)
                    else stringResource(R.string.name_length, name.trim().length, DigitizationTask.MAX_NAME_LENGTH),
                )
            },
            isError = tooLong,
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate() }),
        )
        if (creation == CreationState.Error) {
            Text(stringResource(R.string.create_failed), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate, enabled = canCreate, modifier = Modifier.fillMaxWidth().testTag("createTask")) {
            Text(stringResource(if (saving) R.string.creating else R.string.create))
        }
    }
}

@Composable
private fun TaskDetailScreen(
    state: TaskDetailState,
    sourceModel: SourceViewModel,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    workflow: WorkflowViewModel,
    pages: List<SourceDocument>,
    images: SourceImageLoader,
    onReview: () -> Unit,
    onFields: () -> Unit,
    onData: () -> Unit,
    onStructure: () -> Unit,
) {
    val quality by workflow.state.collectAsStateWithLifecycle()
    val activity by workflow.activity.collectAsStateWithLifecycle()
    val busy by workflow.busy.collectAsStateWithLifecycle()
    var openPage by rememberSaveable { mutableStateOf<String?>(null) }
    var reprocessPage by rememberSaveable { mutableStateOf<String?>(null) }
    Page(title = stringResource(R.string.task_detail), onBack = onBack) {
        when (state) {
            TaskDetailState.Loading -> LoadingTasks()
            TaskDetailState.Error -> LoadError(onRetry)
            is TaskDetailState.Ready -> {
                val task = state.task
                if (task == null) {
                    Text(stringResource(R.string.task_missing))
                } else {
                    Text(task.name, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
                    DetailValue(stringResource(R.string.status_label), stringResource(task.status.label()))
                    WorkflowNotice(workflow)
                    WorkflowLoadState(quality, workflow) { ready ->
                        val data = ready.snapshot.dataset
                        DetailValue(stringResource(R.string.record_count), data?.rows?.count { !it.excluded }?.toString() ?: "0")
                        DetailValue(stringResource(R.string.review_count), if (data == null && ready.snapshot.issues.isEmpty()) "尚未检查" else ReviewQueue.items(ready.snapshot.issues).size.toString())
                        if (activity?.busy == true) Text("正在整理 ${activity?.completed ?: 0} / ${activity?.total ?: 0} 页，离开页面后仍会继续。")
                        else if (task.status == TaskStatus.PROCESSING) Text("整理尚未完成，可以重新执行。", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { workflow.start() }, enabled = pages.isNotEmpty() && !busy && activity?.busy == false,
                            modifier = Modifier.fillMaxWidth().testTag("startProcessing")) { Text("开始整理 / 继续未完成页面") }
                        Text("复杂表头、合并区域或缺线可先确认结构，再继续整理。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick=onStructure,modifier=Modifier.testTag("openStructure")) { Text("确认 / 调整表格结构") }
                        if (data != null) {
                            TextButton(onClick = onFields) { Text("字段设置") }
                            Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("开始检查") }
                            TextButton(onClick = onData) { Text("查看完整数据") }
                            Button(onClick = workflow::requestExport, enabled = task.status in setOf(TaskStatus.READY_TO_EXPORT, TaskStatus.EXPORTED) && !busy && activity?.busy == false,
                                modifier = Modifier.fillMaxWidth()) { Text("导出 XLSX") }
                        } else if (ready.snapshot.issues.isNotEmpty()) TextButton(onClick = onReview) { Text("检查未完成页面") }
                    }
                    Spacer(Modifier.height(32.dp))
                    SourceSection(sourceModel, task.status != TaskStatus.PROCESSING && activity?.busy == false && !busy,
                        onOpen = { openPage = it }, onReprocess = { reprocessPage = it })
                }
            }
        }
    }
    openPage?.let { id -> SourceViewer(pages.singleOrNull { it.id == id }, images) { openPage = null } }
    reprocessPage?.let { id -> AlertDialog(onDismissRequest = { reprocessPage = null }, title = { Text("重新整理此页？") },
        text = { Text("该页已有的整理结果和人工修改将被替换，其他页面保留。原始图片不会改变。") },
        confirmButton = { TextButton(onClick = { reprocessPage = null; workflow.start(id, discardHumanWork = true) }, enabled = !busy && activity?.busy == false) { Text("重新整理") } },
        dismissButton = { TextButton(onClick = { reprocessPage = null }) { Text("取消") } }) }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f))
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
        ) { content() }
    }
}

@Composable
private fun LoadingTasks() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(stringResource(R.string.loading))
    }
}

@Composable
private fun LoadError(onRetry: () -> Unit) {
    Text(stringResource(R.string.load_failed), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
}

private fun TaskStatus.label(): Int = when (this) {
    TaskStatus.DRAFT -> R.string.status_draft
    TaskStatus.CAPTURING -> R.string.status_capturing
    TaskStatus.PROCESSING -> R.string.status_processing
    TaskStatus.REVIEW_REQUIRED -> R.string.status_review_required
    TaskStatus.READY_TO_EXPORT -> R.string.status_ready_to_export
    TaskStatus.EXPORTED -> R.string.status_exported
    TaskStatus.FAILED -> R.string.status_failed
}
