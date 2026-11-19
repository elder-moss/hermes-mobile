package com.eldermoss.hermes.feature.schedules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.net.CronCreateBody
import com.eldermoss.hermes.core.net.CronJob
import com.eldermoss.hermes.core.net.CronJobUpdate
import com.eldermoss.hermes.core.net.SessionInfo
import com.eldermoss.hermes.ui.ConfirmDialog
import com.eldermoss.hermes.ui.EmptyHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SchedulesUi(
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val runsFor: CronJob? = null,
    val runs: List<SessionInfo> = emptyList(),
    val editor: EditorState? = null,
    val deleteTarget: CronJob? = null,
)

data class EditorState(
    val jobId: String? = null, // null = create
    val name: String = "",
    val schedule: String = "",
    val prompt: String = "",
)

class SchedulesViewModel : ViewModel() {
    private val repo = ServiceLocator.connections.cron

    private val _ui = MutableStateFlow(SchedulesUi())
    val ui: StateFlow<SchedulesUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                _ui.value = _ui.value.copy(jobs = repo.list(), loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun togglePause(job: CronJob) {
        viewModelScope.launch {
            try {
                if (job.enabled) repo.pause(job.id) else repo.resume(job.id)
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    fun delete(job: CronJob) {
        viewModelScope.launch {
            try {
                repo.delete(job.id)
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    fun showRuns(job: CronJob) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(runsFor = job, runs = emptyList())
            try {
                val runs = repo.runs(job.id, limit = 15)
                _ui.value = _ui.value.copy(runs = runs.runs)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    fun closeRuns() { _ui.value = _ui.value.copy(runsFor = null, runs = emptyList()) }

    fun openEditor(job: CronJob?) {
        _ui.value = _ui.value.copy(editor = EditorState(
            jobId = job?.id,
            name = job?.name ?: "",
            schedule = job?.scheduleDisplay ?: job?.schedule?.expr ?: "",
            prompt = job?.prompt ?: "",
        ))
    }

    fun closeEditor() { _ui.value = _ui.value.copy(editor = null) }

    fun saveEditor(state: EditorState) {
        viewModelScope.launch {
            try {
                if (state.jobId == null) {
                    repo.create(CronCreateBody(
                        name = state.name.ifBlank { null },
                        prompt = state.prompt,
                        schedule = state.schedule,
                    ))
                } else {
                    repo.update(state.jobId, CronJobUpdate(
                        name = state.name.ifBlank { null },
                        prompt = state.prompt,
                        schedule = state.schedule,
                    ))
                }
                closeEditor()
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    fun requestDelete(job: CronJob) {
        _ui.value = _ui.value.copy(deleteTarget = job)
    }

    fun cancelDelete() { _ui.value = _ui.value.copy(deleteTarget = null) }

    fun confirmDelete(job: CronJob?) {
        if (job != null) delete(job)
        _ui.value = _ui.value.copy(deleteTarget = null)
    }
}

internal fun formatWhen(iso: String?): String {
    if (iso.isNullOrBlank()) return "never"
    return try {
        val t = java.time.OffsetDateTime.parse(iso)
        java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm").format(t)
    } catch (_: Exception) {
        iso.take(16)
    }
}

internal fun formatWhenEpoch(seconds: Double?): String {
    if (seconds == null || seconds <= 0) return "never"
    val t = java.time.Instant.ofEpochSecond(seconds.toLong())
        .atZone(java.time.ZoneId.systemDefault())
    return java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm").format(t)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen() {
    val vm: SchedulesViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Schedules") },
                actions = { IconButton2 { vm.openEditor(null) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (ui.loading && ui.jobs.isEmpty()) EmptyHint("Loading schedules…")
            if (!ui.loading && ui.jobs.isEmpty()) EmptyHint("No scheduled jobs")
            LazyColumn(Modifier.weight(1f)) {
                items(ui.jobs, key = { it.id }) { job ->
                    CronCard(job,
                        onPauseResume = { vm.togglePause(job) },
                        onDelete = { vm.requestDelete(job) },
                        onRuns = { vm.showRuns(job) },
                        onEdit = { vm.openEditor(job) },
                    )
                }
            }
        }
    }

    ui.runsFor?.let { job ->
        AlertDialog(
            onDismissRequest = { vm.closeRuns() },
            title = { Text("Runs - ${job.name ?: job.id}") },
            text = {
                LazyColumn {
                    items(ui.runs, key = { it.id }) { run ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(formatWhenEpoch(run.startedAt), style = MaterialTheme.typography.labelSmall)
                            Text(
                                run.preview?.take(280) ?: "(no output captured)",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 6,
                            )
                        }
                    }
                    if (ui.runs.isEmpty()) item { Text("No runs recorded") }
                }
            },
            confirmButton = { TextButton(onClick = { vm.closeRuns() }) { Text("Close") } },
        )
    }

    ui.editor?.let { editor ->
        var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(editor.name) }
        var schedule by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(editor.schedule) }
        var prompt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(editor.prompt) }
        AlertDialog(
            onDismissRequest = { vm.closeEditor() },
            title = { Text(if (editor.jobId == null) "New schedule" else "Edit schedule") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = schedule, onValueChange = { schedule = it },
                        label = { Text("Schedule (e.g. every 30m or cron expr)") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        label = { Text("Prompt for the agent") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeEditor()
                    vm.saveEditor(editor.copy(name = name, schedule = schedule, prompt = prompt))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { vm.closeEditor() }) { Text("Cancel") } },
        )
    }

    ui.deleteTarget?.let { job ->
        ConfirmDialog(
            title = "Delete schedule",
            message = "Delete \"${job.name ?: job.id}\"?",
            onDismiss = { vm.cancelDelete() },
            onConfirm = { vm.confirmDelete(job) },
        )
    }
}

@Composable
private fun IconButton2(onClick: () -> Unit) {
    FilledIconButton(onClick = onClick) { Icon(Icons.Filled.Add, "New schedule") }
}

@Composable
private fun CronCard(
    job: CronJob,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit,
    onRuns: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onEdit() }) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.name ?: job.id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                val statusColor = when {
                    job.lastError != null -> MaterialTheme.colorScheme.error
                    !job.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(
                    when {
                        job.lastError != null -> "failing"
                        !job.enabled -> "paused"
                        else -> "active"
                    },
                    style = MaterialTheme.typography.labelSmall, color = statusColor,
                )
            }
            Text(job.scheduleDisplay ?: job.schedule?.display ?: "", style = MaterialTheme.typography.bodySmall)
            job.lastError?.let {
                Text("Last error: ${it.take(160)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Last run: ${formatWhen(job.lastRunAt)}   Next: ${formatWhen(job.nextRunAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onPauseResume) { Text(if (job.enabled) "Pause" else "Resume", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onRuns) { Text("Runs", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onDelete) { Text("Delete", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
