package com.eldermoss.hermes.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldermoss.hermes.BuildConfig
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.store.Settings
import com.eldermoss.hermes.core.store.ThemeMode
import com.eldermoss.hermes.ui.KeyValue
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    var serverUrl by remember(settings) { mutableStateOf(settings.serverUrl) }
    var memsUrl by remember(settings) { mutableStateOf(settings.memsUrl) }
    var username by remember(settings) { mutableStateOf(ServiceLocator.settings.getCredentials().first) }
    var password by remember(settings) { mutableStateOf(ServiceLocator.settings.getCredentials().second) }
    var memsToken by remember(settings) { mutableStateOf(ServiceLocator.settings.getMemsToken()) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier
                .then(modifier)
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Connection") {
                OutlinedTextField(
                    value = serverUrl, onValueChange = { serverUrl = it },
                    label = { Text("Gateway URL (tailscale)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Gateway username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Gateway password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            testing = true
                            scope.launch {
                                val client = com.eldermoss.hermes.core.net.HermesClient(
                                    config = { com.eldermoss.hermes.core.net.ServerConfig(serverUrl.trimEnd('/'), username, password) },
                                )
                                status = try {
                                    val probe = client.probe()
                                    val err = client.login()
                                    if (err == null) {
                                        ServiceLocator.settings.setServerUrl(serverUrl)
                                        ServiceLocator.settings.setCredentials(username, password)
                                        "gateway ${probe.version}, login OK - saved"
                                    } else {
                                        "reachable but login failed: $err"
                                    }
                                } catch (e: Exception) {
                                    "unreachable: ${e.message}"
                                }
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "Testing…" else "Test & save") }
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            }

            Section("Memory service (MemS)") {
                OutlinedTextField(
                    value = memsUrl, onValueChange = { memsUrl = it },
                    label = { Text("MemS sidecar URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = memsToken, onValueChange = { memsToken = it },
                    label = { Text("MemS token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    scope.launch {
                        ServiceLocator.settings.setMemsUrl(memsUrl)
                        ServiceLocator.settings.setMemsToken(memsToken)
                    }
                }) { Text("Save") }
            }

            Section("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in ThemeMode.entries) {
                        FilterChip(
                            selected = settings.theme == mode,
                            onClick = { scope.launch { ServiceLocator.settings.setTheme(mode) } },
                            label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            Section("Notifications") {
                Toggle("Cron job completions", settings.notifCronDone) { v -> scope.launch { ServiceLocator.settings.setNotifCronDone(v) } }
                Toggle("Cron job failures", settings.notifCronFail) { v -> scope.launch { ServiceLocator.settings.setNotifCronFail(v) } }
                Toggle("Long task finished", settings.notifAgentDone) { v -> scope.launch { ServiceLocator.settings.setNotifAgentDone(v) } }
                Toggle("Background link service", settings.linkService) { v ->
                    scope.launch {
                        ServiceLocator.settings.setLinkService(v)
                        com.eldermoss.hermes.notify.LinkControl.set(context, v)
                    }
                }
                Text(
                    "The link service keeps a live connection for instant notifications while the app is backgrounded. Without it, cron checks run every 15 minutes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("About") {
                KeyValue("App version", BuildConfig.VERSION_NAME)
                KeyValue("Transport", "Tailscale-only, no public exposure")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
