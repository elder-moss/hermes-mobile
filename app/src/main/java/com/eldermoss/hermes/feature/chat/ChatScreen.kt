package com.eldermoss.hermes.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eldermoss.hermes.core.net.SocketState
import com.eldermoss.hermes.ui.MarkdownText
import com.eldermoss.hermes.ui.OfflineBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(initialSessionId: String? = null) {
    val vm: ChatViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val socketState by vm.socketState.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(initialSessionId) {
        if (!started) {
            started = true
            if (initialSessionId != null) vm.openExisting(initialSessionId) else vm.newSession()
        }
    }
    LaunchedEffect(ui.items.size, ui.items.lastOrNull()) {
        if (ui.items.isNotEmpty()) listState.animateScrollToItem(ui.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title, maxLines = 1) },
                actions = {
                    IconButton(onClick = { vm.newSession() }) { Icon(Icons.Filled.Add, "New chat") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(socketState != SocketState.CONNECTED, when (socketState) {
                SocketState.CONNECTING -> "connecting…"
                SocketState.LOGIN_FAILED -> "login failed (check Settings)"
                else -> "reconnecting…"
            })

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.items.size) { idx ->
                    Bubble(ui.items[idx])
                }
                if (ui.busy && ui.items.lastOrNull() !is ChatItem.Text) {
                    item { Text("…", style = MaterialTheme.typography.bodyLarge) }
                }
            }

            ui.error?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearError() }) { Text("Dismiss") }
                    }
                }
            }

            ui.approval?.let { ap -> ApprovalCard(ap, vm) }
            ui.clarify?.let { cl -> ClarifyCard(cl, vm) }

            Row(
                Modifier.fillMaxWidth().imePadding().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                )
                Spacer(Modifier.padding(2.dp))
                FilledIconButton(
                    onClick = {
                        if (ui.busy) vm.interrupt() else {
                            vm.send(input); input = ""
                        }
                    },
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(if (ui.busy) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send, if (ui.busy) "Stop" else "Send")
                }
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem) {
    when (item) {
        is ChatItem.Text -> {
            val isUser = item.role == "user"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                Surface(
                    color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.widthIn(max = 340.dp),
                ) {
                    MarkdownText(
                        item.text,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
        is ChatItem.Thinking -> {
            Text(
                item.text.ifBlank { "thinking…" },
                style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        is ChatItem.Tool -> ToolCard(item)
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool) {
    var expanded by remember(item.toolId) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable { if (item.result != null) expanded = !expanded },
    ) {
        Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (item.result == null) "⚙ ${item.name}" else "✓ ${item.name}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded && item.result != null) {
            Text(
                item.result!!.take(4000),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .background(Color(0x20808080), RoundedCornerShape(6.dp))
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun ApprovalCard(ap: ApprovalPrompt, vm: ChatViewModel) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Approval needed", style = MaterialTheme.typography.titleSmall)
            if (ap.command.isNotBlank()) Text(ap.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (ap.description.isNotBlank()) Text(ap.description, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in ap.choices.take(3)) {
                    TextButton(onClick = { vm.respondApproval(choice) }) { Text(choice) }
                }
            }
        }
    }
}

@Composable
private fun ClarifyCard(cl: ClarifyPrompt, vm: ChatViewModel) {
    var freeText by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Question", style = MaterialTheme.typography.titleSmall)
            Text(cl.question, style = MaterialTheme.typography.bodyMedium)
            if (cl.choices.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in cl.choices.take(3)) {
                        TextButton(onClick = { vm.respondClarify(choice) }) { Text(choice) }
                    }
                }
            }
            OutlinedTextField(value = freeText, onValueChange = { freeText = it }, placeholder = { Text("Or type an answer") })
            TextButton(onClick = { if (freeText.isNotBlank()) vm.respondClarify(freeText) }) { Text("Answer") }
        }
    }
}
