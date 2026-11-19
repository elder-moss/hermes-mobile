package com.eldermoss.hermes.feature.sessions

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.eldermoss.hermes.core.net.SearchHit
import com.eldermoss.hermes.core.net.SessionInfo
import com.eldermoss.hermes.ui.ConfirmDialog
import com.eldermoss.hermes.ui.EmptyHint
import com.eldermoss.hermes.ui.ErrorCard
import com.eldermoss.hermes.ui.TextPromptDialog
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class SessionsUi(
    val sessions: List<SessionInfo> = emptyList(),
    val searchResults: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val renameTarget: SessionInfo? = null,
    val deleteTarget: SessionInfo? = null,
)

class SessionsViewModel : ViewModel() {
    private val repo = ServiceLocator.connections.sessions

    private val _ui = MutableStateFlow(SessionsUi())
    val ui: StateFlow<SessionsUi> = _ui.asStateFlow()

    private val query = MutableStateFlow("")

    fun requestRename(row: SessionInfo) { _ui.value = _ui.value.copy(renameTarget = row) }
    fun requestDelete(row: SessionInfo) { _ui.value = _ui.value.copy(deleteTarget = row) }
    fun cancelDialogs() { _ui.value = _ui.value.copy(renameTarget = null, deleteTarget = null) }

    init {
        refresh()
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query.debounce(300).collect { q ->
                if (q.isBlank()) {
                    _ui.value = _ui.value.copy(searchResults = emptyList(), searching = false)
                } else {
                    _ui.value = _ui.value.copy(searching = true)
                    try {
                        val res = repo.search(q)
                        _ui.value = _ui.value.copy(searchResults = res.results, searching = false)
                    } catch (e: Exception) {
                        _ui.value = _ui.value.copy(searching = false, error = e.message)
                    }
                }
            }
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val page = repo.list(limit = 60)
                _ui.value = _ui.value.copy(sessions = page.sessions, loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch {
            try {
                repo.rename(id, title)
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repo.delete(id)
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(onOpen: (String) -> Unit) {
    val vm: SessionsViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var queryText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Sessions") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it; vm.setQuery(it) },
                placeholder = { Text("Search sessions") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            ui.error?.let { err -> ErrorCard(err, onRetry = { vm.refresh() }) }
            val searching = queryText.isNotBlank()
            val list: List<Any> = if (searching) ui.searchResults else ui.sessions
            if (ui.loading && list.isEmpty() && ui.error == null) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.padding(24.dp),
                )
            } else if (!ui.loading && list.isEmpty() && ui.error == null) {
                EmptyHint(if (searching) "No matches" else "No sessions yet")
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list) { row ->
                    when (row) {
                        is SessionInfo -> SessionRow(
                            row,
                            onOpen = { onOpen(row.id) },
                            onRename = { vm.requestRename(row) },
                            onDelete = { vm.requestDelete(row) },
                        )
                        is SearchHit -> SearchRow(row, onOpen = { onOpen(row.sessionId) })
                    }
                }
            }
        }
    }

    if (ui.renameTarget != null) {
        val t = ui.renameTarget
        TextPromptDialog(
            title = "Rename session",
            initial = t?.title ?: "",
            onDismiss = { vm.cancelDialogs() },
            onConfirm = { v -> vm.cancelDialogs(); t?.let { vm.rename(it.id, v) } },
        )
    }
    if (ui.deleteTarget != null) {
        val t = ui.deleteTarget
        ConfirmDialog(
            title = "Delete session",
            message = "Delete \"${t?.title ?: t?.id}\" permanently?",
            onDismiss = { vm.cancelDialogs() },
            onConfirm = { vm.cancelDialogs(); t?.let { vm.delete(it.id) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(row: SessionInfo, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(onClick = onOpen, onLongClick = onRename)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title?.ifBlank { null } ?: "(untitled)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    formatTime(row.lastActivityAt ?: row.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.preview.isNullOrBlank()) {
                Text(row.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            val tags = buildString {
                if (row.pinned) append(" · pinned")
                if (row.source == "cron") append(" · cron")
                if (row.unread) append(" · unread")
            }
            Text(
                "${row.messageCount} messages$tags",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                androidx.compose.material3.TextButton(onClick = onRename, enabled = true) { Text("Rename", style = MaterialTheme.typography.labelSmall) }
                androidx.compose.material3.TextButton(onClick = onDelete) { Text("Delete", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchRow(hit: SearchHit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).combinedClickable(onClick = onOpen)) {
        Column(Modifier.padding(10.dp)) {
            Text(hit.title ?: "(untitled)", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(hit.snippet ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

internal fun formatTime(epochSeconds: Double?): String = when {
    epochSeconds == null || epochSeconds <= 0 -> ""
    else -> timeFormat.format(Date((epochSeconds * 1000).toLong()))
}
