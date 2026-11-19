package com.eldermoss.hermes

import android.content.Context
import com.eldermoss.hermes.core.net.ConnectionManager
import com.eldermoss.hermes.core.store.SettingsRepository

/** Manual DI: one graph, built once. No framework — the app is small on purpose. */
object ServiceLocator {
    lateinit var settings: SettingsRepository
        private set
    lateinit var connections: ConnectionManager
        private set

    fun init(context: Context) {
        settings = SettingsRepository(context)
        connections = ConnectionManager(settings)
    }
}
