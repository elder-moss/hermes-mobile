package com.eldermoss.hermes.feature.memory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
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
import com.eldermoss.hermes.core.data.MemRepository
import com.eldermoss.hermes.core.net.MemRecord
import com.eldermoss.hermes.ui.ConfirmDialog
import com.eldermoss.hermes.ui.EmptyHint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

data class MemUi(
    val records: List<MemRecord> = emptyList(),
    val searching: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val detail: MemRecord? = null,
    val editor: MemEditor? = null,
    val retractTarget: MemRecord? = null,
)

data class MemEditor(
    val mode: String, // "add" | "edit"
    val targetId: Long? = null,
    val text: String = "",
    val category: String = "fact",
    val importance: Int = 3,
    val confidence: String = "1.0",
    val occurredAt: String = "",
)

class MemoryViewModel : ViewModel() {
    private val repo = ServiceLocator.connections.mems

    private val _ui = MutableStateFlow(MemUi())
    val ui: StateFlow<MemUi> = _ui.asStateFlow()

    private val query = MutableStateFlow("")

    init {
        refresh()
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query.debounce(350).collect { q ->
                if (q.isBlank()) {
                    _ui.value = _ui.value.copy(searching = false)
                    refresh()
                } else {
                    try {
                        val res = repo.search(q)
                        _ui.value = _ui.value.copy(records = res, searching = true, loading = false)
                    } catch (e: Exception) {
                        _ui.value = _ui.value.copy(searching = false, error = e.message)
                    }
                }
            }
        }
    }

    fun setQuery(q: String) { query.value = q }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                _ui.value = _ui.value.copy(records = repo.list(), loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = memError(e))
            }
        }
    }

    private fun memError(e: Exception): String {
        val msg = e.message ?: "error"
        return if (msg.contains("401") || msg.contains("403"))
            "Memory service rejected the token - set the MemS token in Settings"
        else msg
    }

    fun openEditor(record: MemRecord?) {
        _ui.value = _ui.value.copy(editor = if (record == null) MemEditor(mode = "add") else MemEditor(
            mode = "edit",
            targetId = record.id,
            text = record.text,
            category = record.category,
            importance = record.importance,
            confidence = record.confidence.toString(),
            occurredAt = record.occurredAt ?: "",
        ))
    }

    fun closeEditor() { _ui.value = _ui.value.copy(editor = null) }

    fun updateEditor(editor: MemEditor) { _ui.value = _ui.value.copy(editor = editor) }

    fun saveEditor() {
        val editor = _ui.value.editor ?: return
        viewModelScope.launch {
            try {
                val write = MemRepository.MemWrite(
                    text = editor.text.trim(),
                    category = editor.category.ifBlank { null },
                    importance = editor.importance,
                    confidence = editor.confidence.toDoubleOrNull(),
                    occurredAt = editor.occurredAt.ifBlank { null },
                    source = if (editor.mode == "add") "app edit" else "user correction",
                )
                if (editor.mode == "add") repo.add(write) else repo.correct(editor.targetId!!, write)
                closeEditor()
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = memError(e))
            }
        }
    }

    fun requestRetract(record: MemRecord) { _ui.value = _ui.value.copy(retractTarget = record) }

    fun cancelRetract() { _ui.value = _ui.value.copy(retractTarget = null) }

    fun retract(record: MemRecord) {
        viewModelScope.launch {
            try {
                repo.retract(record.id)
                _ui.value = _ui.value.copy(retractTarget = null)
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(retractTarget = null, error = memError(e))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemoryScreen() {
    val vm: MemoryViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var queryText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                actions = { FilledIconButton(onClick = { vm.openEditor(null) }) { Icon(Icons.Filled.Add, "Add memory") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it; vm.setQuery(it) },
                placeholder = { Text("Search memories") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (ui.loading && ui.records.isEmpty()) EmptyHint("Loading memories…")
            if (!ui.loading && ui.records.isEmpty()) EmptyHint("No memories yet")
            LazyColumn(Modifier.weight(1f)) {
                items(ui.records, key = { it.id }) { record ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .combinedClickable(
                                onClick = { vm.openEditor(record) },
                                onLongClick = { vm.requestRetract(record) },
                            ),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${record.category} · ${record.importance}/5" +
                                        if (record.status != "active") " · ${record.status}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    record.learnedAt?.take(10) ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(record.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                            if (record.semanticSimilarity != null) {
                                Text(
                                    "match ${(record.semanticSimilarity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ui.editor?.let { editor ->
        AlertDialog(
            onDismissRequest = { vm.closeEditor() },
            title = { Text(if (editor.mode == "add") "Add memory" else "Correct memory #${editor.targetId}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editor.text,
                        onValueChange = { vm.updateEditor(editor.copy(text = it)) },
                        label = { Text("Text") },
                        minLines = 3,
                    )
                    OutlinedTextField(
                        value = editor.category,
                        onValueChange = { vm.updateEditor(editor.copy(category = it)) },
                        label = { Text("Category") },
                        singleLine = true,
                    )
                    Row {
                        OutlinedTextField(
                            value = editor.importance.toString(),
                            onValueChange = { v -> vm.updateEditor(editor.copy(importance = v.toIntOrNull()?.coerceIn(1, 5) ?: editor.importance)) },
                            label = { Text("Importance 1-5") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).padding(end = 4.dp),
                        )
                        OutlinedTextField(
                            value = editor.confidence,
                            onValueChange = { vm.updateEditor(editor.copy(confidence = it)) },
                            label = { Text("Confidence 0-1") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = editor.occurredAt,
                        onValueChange = { vm.updateEditor(editor.copy(occurredAt = it)) },
                        label = { Text("Event date (ISO, optional)") },
                        singleLine = true,
                    )
                    if (editor.mode == "edit") {
                        Text(
                            "Saving supersedes the old record (history preserved, new text re-embedded).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveEditor() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.closeEditor() }) { Text("Cancel") } },
        )
    }

    ui.retractTarget?.let { record ->
        ConfirmDialog(
            title = "Retract memory",
            message = "Hide memory #${record.id} from recall? History stays.",
            confirmLabel = "Retract",
            onDismiss = { vm.cancelRetract() },
            onConfirm = { vm.retract(record) },
        )
    }
}
