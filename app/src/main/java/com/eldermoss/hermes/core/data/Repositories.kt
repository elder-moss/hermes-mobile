package com.eldermoss.hermes.core.data

import com.eldermoss.hermes.core.net.ApiError
import com.eldermoss.hermes.core.net.CronCreateBody
import com.eldermoss.hermes.core.net.CronJob
import com.eldermoss.hermes.core.net.CronJobUpdate
import com.eldermoss.hermes.core.net.CronRuns
import com.eldermoss.hermes.core.net.CronUpdateBody
import com.eldermoss.hermes.core.net.HermesClient
import com.eldermoss.hermes.core.net.lenientJson
import com.eldermoss.hermes.core.net.MessagesPage
import com.eldermoss.hermes.core.net.MemRecord
import com.eldermoss.hermes.core.net.OkResponse
import com.eldermoss.hermes.core.net.SearchResponse
import com.eldermoss.hermes.core.net.SessionInfo
import com.eldermoss.hermes.core.net.SessionPage
import com.eldermoss.hermes.core.net.SkillContent
import com.eldermoss.hermes.core.net.SkillInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Sessions: list, search, open transcript, rename, delete. */
class SessionsRepository(private val client: HermesClient) {

    suspend fun list(limit: Int = 40, archived: String = "exclude"): SessionPage {
        val el = client.getJson("/api/sessions?limit=$limit&offset=0&min_messages=0&archived=$archived&order=recent")
        return decode(el)
    }

    suspend fun search(query: String): SearchResponse =
        decode(client.getJson("/api/sessions/search?q=" + urlEncode(query)))

    suspend fun messages(id: String, limit: Int = 120, order: String = "latest"): MessagesPage =
        decode(client.getJson("/api/sessions/$id/messages?limit=$limit&offset=0&order=$order&include_compacted=true"))

    suspend fun rename(id: String, title: String): OkResponse {
        val body = buildJsonObject { put("title", title) }
        return decode(client.call("PATCH", "/api/sessions/$id", body))
    }

    suspend fun delete(id: String): OkResponse = decode(client.deleteJson("/api/sessions/$id"))

    private inline fun <reified T> decode(el: kotlinx.serialization.json.JsonElement): T =
        lenientJson.decodeFromJsonElement<T>(el)
}

/** Skills: list installed skills, view contents, enable/disable. */
class SkillsRepository(private val client: HermesClient) {

    suspend fun list(): List<SkillInfo> = decodeList(client.getJson("/api/skills"))

    suspend fun content(name: String): SkillContent =
        decode(client.getJson("/api/skills/content?name=" + urlEncode(name)))

    suspend fun setEnabled(name: String, enabled: Boolean): OkResponse {
        val body = buildJsonObject { put("name", name); put("enabled", enabled) }
        return decode(client.putJson("/api/skills/toggle", body))
    }

    private inline fun <reified T> decode(el: kotlinx.serialization.json.JsonElement): T =
        lenientJson.decodeFromJsonElement<T>(el)

    private inline fun <reified T> decodeList(el: kotlinx.serialization.json.JsonElement): List<T> =
        lenientJson.decodeFromJsonElement<List<T>>(el)
}

/** Cron jobs: full CRUD plus pause/resume/trigger and recent runs. */
class CronRepository(private val client: HermesClient) {

    suspend fun list(): List<CronJob> = lenientJson.decodeFromJsonElement(client.getJson("/api/cron/jobs"))

    suspend fun runs(jobId: String, limit: Int = 10): CronRuns =
        decode(client.getJson("/api/cron/jobs/$jobId/runs?limit=$limit"))

    suspend fun create(body: CronCreateBody): CronJob = decode(client.postJson("/api/cron/jobs", toJson(body)))
    suspend fun update(jobId: String, updates: CronJobUpdate): CronJob =
        decode(client.call("PUT", "/api/cron/jobs/$jobId", toJson(CronUpdateBody(updates))))

    suspend fun pause(jobId: String): CronJob = decode(client.postJson("/api/cron/jobs/$jobId/pause"))
    suspend fun resume(jobId: String): CronJob = decode(client.postJson("/api/cron/jobs/$jobId/resume"))
    suspend fun delete(jobId: String): OkResponse = decode(client.deleteJson("/api/cron/jobs/$jobId"))

    private inline fun <reified T> decode(el: kotlinx.serialization.json.JsonElement): T =
        lenientJson.decodeFromJsonElement<T>(el)

    private inline fun <reified T : Any> toJson(v: T): JsonObject =
        lenientJson.parseToJsonElement(lenientJson.encodeToString(v)) as JsonObject
}

/**
 * MemS records via the Asgard sidecar (server/mems_api.py), which wraps the
 * actual MemS plugin so supersession, retraction and re-embedding stay
 * consistent with the agent's own writes. Bearer-token auth.
 */
class MemRepository(
    private val memsUrl: () -> String,
    private val token: () -> String,
    http: OkHttpClient? = null,
) {
    private val http: OkHttpClient = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // embeds can take a moment
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun search(query: String): List<MemRecord> = listCall("search?q=" + urlEncode(query))
    suspend fun list(): List<MemRecord> = listCall("list")
    suspend fun get(id: Long): MemRecord = raw("GET", "mems/$id", null)

    @Serializable
    private data class RetractBody(val id: Long)

    @Serializable
    data class MemWrite(
        val id: Long = 0,
        val text: String = "",
        val category: String? = null,
        val importance: Int? = null,
        @SerialName("occurred_at") val occurredAt: String? = null,
        @SerialName("valid_from") val validFrom: String? = null,
        @SerialName("valid_to") val validTo: String? = null,
        val confidence: Double? = null,
        val source: String? = null,
    )

    suspend fun add(record: MemWrite): MemRecord = raw("POST", "mems/add", record)
    suspend fun correct(id: Long, record: MemWrite): MemRecord =
        raw("POST", "mems/correct", record.copy(id = id))
    suspend fun retract(id: Long): JsonObject = raw("POST", "mems/retract", RetractBody(id))

    private suspend fun listCall(path: String): List<MemRecord> {
        val text = fetch("GET", path, null)
        return json.decodeFromJsonElement(json.parseToJsonElement(text))
    }

    private fun fetch(method: String, path: String, body: Any?): String {
        val base = memsUrl().trimEnd('/')
        val rb = Request.Builder().url("$base/api/$path").header("Authorization", "Bearer ${token()}")
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            else -> rb.post(json.encodeToString(body ?: JsonObject(emptyMap())).toRequestBody(jsonType))
        }
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (r.code !in 200..299) throw ApiError(r.code, "MemS HTTP ${r.code}: ${text.take(140)}")
            return text
        }
    }

    private inline fun <reified T> raw(method: String, path: String, body: Any?): T =
        json.decodeFromString(fetch(method, path, body))
}

fun urlEncode(raw: String): String = java.net.URLEncoder.encode(raw, "UTF-8")
