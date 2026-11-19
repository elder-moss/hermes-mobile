package com.eldermoss.hermes.feature.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.net.SkillContent
import com.eldermoss.hermes.core.net.SkillInfo
import com.eldermoss.hermes.ui.EmptyHint
import com.eldermoss.hermes.ui.MarkdownText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SkillsUi(
    val skills: List<SkillInfo> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val open: SkillContent? = null,
)

class SkillsViewModel : ViewModel() {
    private val repo = ServiceLocator.connections.skills

    private val _ui = MutableStateFlow(SkillsUi())
    val ui: StateFlow<SkillsUi> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                _ui.value = SkillsUi(skills = repo.list(), loading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun toggle(skill: SkillInfo) {
        viewModelScope.launch {
            // Optimistic flip; revert on failure.
            val flipped = skill.copy(enabled = !skill.enabled)
            _ui.value = _ui.value.copy(skills = _ui.value.skills.map { if (it.name == skill.name) flipped else it })
            try {
                repo.setEnabled(skill.name, !skill.enabled)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(skills = _ui.value.skills.map { if (it.name == skill.name) skill else it }, error = e.message)
            }
        }
    }

    fun open(name: String) {
        viewModelScope.launch {
            try {
                _ui.value = _ui.value.copy(open = repo.content(name))
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            }
        }
    }

    fun close() { _ui.value = _ui.value.copy(open = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen() {
    val vm: SkillsViewModel = viewModel()
    val ui by vm.ui.collectAsState()

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Skills") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (ui.loading && ui.skills.isEmpty()) EmptyHint("Loading skills…")
            LazyColumn(Modifier.weight(1f)) {
                items(ui.skills, key = { it.name }) { skill ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.open(skill.name) }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                Text(
                                    skill.provenance ?: "skill",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = skill.enabled, onCheckedChange = { vm.toggle(skill) })
                        }
                    }
                }
            }
        }
    }

    ui.open?.let { content ->
        AlertDialog(
            onDismissRequest = { vm.close() },
            title = { Text(content.name) },
            text = {
                LazyColumn {
                    item {
                        MarkdownText(
                            content.content,
                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.close() }) { Text("Close") } },
        )
    }
}
