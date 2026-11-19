package com.eldermoss.hermes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eldermoss.hermes.core.store.Settings
import com.eldermoss.hermes.core.store.ThemeMode
import com.eldermoss.hermes.ui.AppRoot
import com.eldermoss.hermes.ui.theme.HermesTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        com.eldermoss.hermes.notify.AppForeground.foreground = true
    }

    override fun onStop() {
        super.onStop()
        com.eldermoss.hermes.notify.AppForeground.foreground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by ServiceLocator.settings.settings
                .collectAsStateWithLifecycle(initialValue = Settings())
            HermesTheme(
                mode = if (settings == Settings()) ThemeMode.SYSTEM else settings.theme,
            ) {
                AppRoot(settings)
            }
        }
    }
}
