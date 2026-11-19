package com.eldermoss.hermes.core.store

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hermes_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val serverUrl: String = DEFAULT_SERVER,
    val memsUrl: String = DEFAULT_MEMS,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notifCronDone: Boolean = true,
    val notifCronFail: Boolean = true,
    val notifAgentDone: Boolean = true,
    val linkService: Boolean = false,
) {
    companion object {
        const val DEFAULT_SERVER = ""
        const val DEFAULT_MEMS = ""
    }
}

/**
 * Non-secret settings in DataStore; credentials in EncryptedSharedPreferences
 * (AES256 keys held by the Android Keystore). Nothing secret is ever written
 * to plain prefs, logs, or the repo.
 */
class SettingsRepository(private val context: Context) {

    private val secret: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "hermes_secret", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val keyServer = stringPreferencesKey("server_url")
    private val keyMems = stringPreferencesKey("mems_url")
    private val keyTheme = stringPreferencesKey("theme")
    private val keyNotifCronDone = stringPreferencesKey("notif_cron_done")
    private val keyNotifCronFail = stringPreferencesKey("notif_cron_fail")
    private val keyNotifAgentDone = stringPreferencesKey("notif_agent_done")
    private val keyLinkService = stringPreferencesKey("link_service")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            serverUrl = p[keyServer] ?: Settings.DEFAULT_SERVER,
            memsUrl = p[keyMems] ?: Settings.DEFAULT_MEMS,
            theme = enumOr(p[keyTheme], ThemeMode.SYSTEM),
            notifCronDone = p[keyNotifCronDone] != "0",
            notifCronFail = p[keyNotifCronFail] != "0",
            notifAgentDone = p[keyNotifAgentDone] != "0",
            linkService = p[keyLinkService] == "1",
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    suspend fun setServerUrl(v: String) = edit { it[keyServer] = v.trim().trimEnd('/') }
    suspend fun setMemsUrl(v: String) = edit { it[keyMems] = v.trim().trimEnd('/') }
    suspend fun setTheme(v: ThemeMode) = edit { it[keyTheme] = v.name }
    suspend fun setNotifCronDone(v: Boolean) = edit { it[keyNotifCronDone] = if (v) "1" else "0" }
    suspend fun setNotifCronFail(v: Boolean) = edit { it[keyNotifCronFail] = if (v) "1" else "0" }
    suspend fun setNotifAgentDone(v: Boolean) = edit { it[keyNotifAgentDone] = if (v) "1" else "0" }
    suspend fun setLinkService(v: Boolean) = edit { it[keyLinkService] = if (v) "1" else "0" }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T =
        raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    // ---- credentials (encrypted at rest) ----

    fun getCredentials(): Pair<String, String> =
        (secret.getString("username", "") ?: "") to (secret.getString("password", "") ?: "")

    fun setCredentials(username: String, password: String) {
        secret.edit().putString("username", username).putString("password", password).apply()
    }

    fun getMemsToken(): String = secret.getString("mems_token", "") ?: ""

    fun setMemsToken(token: String) {
        secret.edit().putString("mems_token", token).apply()
    }
}
