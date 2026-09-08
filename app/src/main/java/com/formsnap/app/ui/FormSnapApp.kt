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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.formsnap.app.R
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus

@Composable
fun FormSnapApp(model: TaskViewModel) {
    val navigation = rememberNavController()
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val name by model.taskName.collectAsStateWithLifecycle()
    val creation by model.creation.collectAsStateWithLifecycle()
    val createdId by model.createdTaskId.collectAsStateWithLifecycle()

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
            val detail by remember(model, id) { model.observeTask(id) }
                .collectAsStateWithLifecycle(initialValue = TaskDetailState.Loading)
            TaskDetailScreen(
                state = detail,
                onBack = { navigation.popBackStack() },
                onRetry = model::retryLoading,
            )
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
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
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
                    // Phase 0 cannot attach pages or create rows. These are empty counts, not results.
                    DetailValue(stringResource(R.string.source_count), stringResource(R.string.zero_count))
                    DetailValue(stringResource(R.string.record_count), stringResource(R.string.zero_count))
                    DetailValue(stringResource(R.string.review_count), stringResource(R.string.not_checked))
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(R.string.no_sources), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.phase_zero_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
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
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
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
