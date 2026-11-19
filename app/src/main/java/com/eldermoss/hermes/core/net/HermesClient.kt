package com.eldermoss.hermes.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Snapshot of what a request needs; the UI layer never touches this directly. */
data class ServerConfig(val baseUrl: String, val username: String, val password: String)

/**
 * The only class that knows the Hermes gateway's HTTP surface: cookie login,
 * bearer-less REST under /api, and one-use WS tickets. All calls are suspend
 * and run off the main thread. On a 401 the client re-logs in once and retries.
 */
class HermesClient(
    private val config: () -> ServerConfig,
    http: OkHttpClient? = null,
) {
    val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val loginMutex = Mutex()

    @Volatile
    private var cookie: String? = null

    fun baseUrl(): String = config().baseUrl.trimEnd('/')

    /** Sets an externally obtained cookie (used by tests and the socket layer). */
    fun setCookie(value: String?) { cookie = value }

    fun currentCookie(): String? = cookie

    private fun Request.Builder.withCookie(): Request.Builder =
        cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } ?: this

    private fun parseCookies(response: Response): String {
        val out = StringBuilder()
        val seen = HashSet<String>()
        for (raw in response.headers("Set-Cookie")) {
            val pair = raw.split(";")[0]
            val name = pair.split("=")[0].trim()
            if (seen.add(name)) {
                if (out.isNotEmpty()) out.append("; ")
                out.append(pair)
            }
        }
        return out.toString()
    }

    /** POST /auth/password-login. Returns null message on success, error text otherwise. */
    suspend fun login(): String? = withContext(Dispatchers.IO) {
        loginMutex.withLock {
            val cfg = config()
            val base = cfg.baseUrl.trimEnd('/')
            val providers = try {
                getJson("/api/status", auth = false).let { status ->
                    (status as? JsonObject)?.get("auth_providers")
                        ?.toString()?.trim('[', ']', '"', ' ')?.split(",")?.firstOrNull()?.trim('"', ' ')
                        ?: "basic"
                }
            } catch (_: Exception) { "basic" }
            val body = buildJsonObject {
                put("provider", providers.ifBlank { "basic" })
                put("username", cfg.username)
                put("password", cfg.password)
            }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("$base/auth/password-login").post(body).build()
            try {
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 200 -> { cookie = parseCookies(r); null }
                        r.code == 401 -> "Invalid username or password"
                        r.code == 429 -> "Too many login attempts, try again shortly"
                        else -> "Login failed (HTTP ${r.code})"
                    }
                }
            } catch (e: Exception) {
                "Gateway unreachable: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun execute(method: String, path: String, body: JsonElement?, auth: Boolean, timeoutSecs: Long): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val base = baseUrl()
            val rb = Request.Builder().url("$base$path").withCookie()
            when (method) {
                "GET" -> rb.get()
                "DELETE" -> rb.delete()
                "PUT" -> rb.put((body?.toString() ?: "{}").toRequestBody(jsonType))
                "PATCH" -> rb.patch((body?.toString() ?: "{}").toRequestBody(jsonType))
                else -> rb.post((body?.toString() ?: "{}").toRequestBody(jsonType))
            }
            val call = if (timeoutSecs > 0) {
                http.newBuilder().callTimeout(timeoutSecs, TimeUnit.SECONDS).build().newCall(rb.build())
            } else {
                http.newCall(rb.build())
            }
            try {
                call.execute().use { r -> r.code to (r.body?.string() ?: "") }
            } catch (e: Exception) {
                throw ApiError(0, e.message ?: e.javaClass.simpleName)
            }
        }

    /** One REST call with the one-shot 401 retry. Returns parsed body as JsonElement. */
    suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        auth: Boolean = true,
        timeoutSecs: Long = 60,
    ): JsonElement {
        var (code, text) = execute(method, path, body, auth, timeoutSecs)
        if (auth && code == 401) {
            val err = login()
            if (err != null) throw ApiError(401, err)
            val retried = execute(method, path, body, auth, timeoutSecs)
            code = retried.first
            text = retried.second
        }
        if (code !in 200..299) throw ApiError(code, "HTTP $code: ${text.take(160)}")
        return lenientJson.parseToJsonElement(text.ifBlank { "{}" })
    }

    suspend fun getJson(path: String, auth: Boolean = true, timeoutSecs: Long = 60): JsonElement =
        call("GET", path, auth = auth, timeoutSecs = timeoutSecs)

    suspend fun postJson(path: String, body: JsonElement? = null, timeoutSecs: Long = 60): JsonElement =
        call("POST", path, body, timeoutSecs = timeoutSecs)

    suspend fun putJson(path: String, body: JsonElement? = null): JsonElement = call("PUT", path, body)

    suspend fun deleteJson(path: String): JsonElement = call("DELETE", path)

    /** POST /api/auth/ws-ticket -> one-use ticket for the WebSocket dial. */
    suspend fun wsTicket(): String {
        val el = call("POST", "/api/auth/ws-ticket", buildJsonObject { })
        val obj = el as? JsonObject ?: throw ApiError(500, "ws-ticket: unexpected response")
        return obj["ticket"]?.toString()?.trim('"') ?: throw ApiError(500, "ws-ticket: missing ticket")
    }

    /** True when the gateway answers at all (used by Settings "Test connection"). */
    suspend fun probe(): StatusProbe {
        val el = getJson("/api/status", auth = false, timeoutSecs = 10)
        val obj = el as? JsonObject
        return StatusProbe(
            version = obj?.get("version")?.toString()?.trim('"') ?: "?",
            gatewayRunning = obj?.get("gateway_running")?.toString() == "true",
            authRequired = obj?.get("auth_required")?.toString() == "true",
        )
    }
}

data class StatusProbe(val version: String, val gatewayRunning: Boolean, val authRequired: Boolean)
