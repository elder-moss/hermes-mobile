package com.eldermoss.hermes.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eldermoss.hermes.ui.ConnectionBanner
import com.eldermoss.hermes.ui.ErrorNote
import com.eldermoss.hermes.ui.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(initialSessionId: String? = null) {
    val vm: ChatViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val socketState by vm.socketState.collectAsState()
    val socketDetail by vm.socketDetail.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(initialSessionId) {
        if (initialSessionId != null) vm.openExisting(initialSessionId) else vm.newSession()
    }
    LaunchedEffect(ui.items.size) {
        if (ui.items.isNotEmpty()) listState.animateScrollToItem(ui.items.lastIndex)
    }
    LaunchedEffect(ui.error) {
        if (ui.error != null) {
            kotlinx.coroutines.delay(6000)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.title, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = { vm.newSession() }) { Icon(Icons.Filled.Add, "New chat") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(socketState, socketDetail, onRetry = { vm.reconnect() })

            if (ui.items.isEmpty() && !ui.busy) {
                // Centered empty state instead of a void.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Hermes", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "Say something. This conversation follows you\nto every screen the agent lives on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(0.85f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ui.items.size) { idx -> Bubble(ui.items[idx]) }
                    if (ui.busy) { item { ThinkingRow() } }
                }
            }

            ui.error?.let { err -> ErrorNote(err, onDismiss = { vm.clearError() }) }
            ui.approval?.let { ap -> ApprovalCard(ap, vm) }
            ui.clarify?.let { cl -> ClarifyCard(cl, vm) }

            Row(
                Modifier
                    .fillMaxWidth()
                    // Some OEMs report the nav-bar height inside the IME inset even
                    // with the keyboard closed — exclude it or the composer floats.
                    .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Spacer(Modifier.size(6.dp))
                val sending = ui.busy
                FilledIconButton(
                    onClick = { if (sending) vm.interrupt() else { vm.send(input); input = "" } },
                    modifier = Modifier
                        .size(52.dp)
                        .semantics { contentDescription = if (sending) "Stop" else "Send" },
                    shape = CircleShape,
                ) {
                    Icon(if (sending) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send, null)
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    val alpha = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseA",
    ).value
    Text(
        "Elder is thinking…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp).alpha(alpha),
    )
}

@Composable
private fun Bubble(item: ChatItem) {
    when (item) {
        is ChatItem.Text -> {
            val isUser = item.role == "user"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                Surface(
                    color = if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    ),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    MarkdownText(item.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
        is ChatItem.Thinking -> {
            Text(
                item.text.ifBlank { "thinking…" },
                style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        is ChatItem.Tool -> ToolCard(item)
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool) {
    var expanded by remember(item.toolId) { mutableStateOf(false) }
    val done = item.result != null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (done) 0.dp else 0.dp)
            .clickable { if (done) expanded = !expanded },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (done) "✓" else "◔",
                style = MaterialTheme.typography.labelMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (done) {
                Text("tap", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded && done) {
            Text(
                item.result!!.take(4000),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun ApprovalCard(ap: ApprovalPrompt, vm: ChatViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Approval needed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (ap.command.isNotBlank()) Text(
                ap.command, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (ap.description.isNotBlank()) Text(
                ap.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Question", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(cl.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
