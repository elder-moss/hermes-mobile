package com.eldermoss.hermes.core.net

import com.eldermoss.hermes.core.data.CronRepository
import com.eldermoss.hermes.core.data.MemRepository
import com.eldermoss.hermes.core.data.SessionsRepository
import com.eldermoss.hermes.core.data.SkillsRepository
import com.eldermoss.hermes.core.store.Settings
import com.eldermoss.hermes.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the live client + socket and keeps their config in sync with Settings.
 * Changing the server URL drops the cookie and reconnects.
 */
class ConnectionManager(private val settings: SettingsRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var current = ServerConfig(Settings.DEFAULT_SERVER, "", "")
    @Volatile private var memsToken: String = ""

    val client = HermesClient(config = { current })

    val socket = GatewaySocket(client)
    val sessions = SessionsRepository(client)
    val skills = SkillsRepository(client)
    val cron = CronRepository(client)
    val mems = MemRepository(memsUrl = { currentMemsUrl() }, token = { memsToken })

    @Volatile private var memsUrl: String = Settings.DEFAULT_MEMS
    private fun currentMemsUrl(): String = memsUrl

    init {
        scope.launch {
            settings.settings.collect { s ->
                val creds = settings.getCredentials()
                val next = ServerConfig(s.serverUrl, creds.first, creds.second)
                if (next.baseUrl != current.baseUrl) {
                    client.setCookie(null)
                    socket.stop()
                }
                current = next
                memsUrl = s.memsUrl
                memsToken = settings.getMemsToken()
                // The chat socket is the app's lifeline: run it whenever we have
                // credentials. linkService only controls the background foreground service.
                if (creds.second.isNotBlank()) socket.start()
            }
        }
    }
}
