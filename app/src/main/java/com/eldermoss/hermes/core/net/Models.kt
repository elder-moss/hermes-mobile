package com.eldermoss.hermes.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionInfo(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_activity_at") val lastActivityAt: Double? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val unread: Boolean = false,
    val source: String? = null,
    val profile: String? = null,
)

@Serializable
data class SessionPage(
    val sessions: List<SessionInfo> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class SessionMessage(
    val role: String = "",
    val content: String? = null,
    @SerialName("display_content") val displayContent: String? = null,
    val name: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val id: String? = null,
)

@Serializable
data class MessagesPage(
    @SerialName("session_id") val sessionId: String? = null,
    val messages: List<SessionMessage> = emptyList(),
    val pagination: Pagination? = null,
) {
    @Serializable
    data class Pagination(val limit: Int = 0, val offset: Int = 0, val order: String? = null, val returned: Int = 0)
}

@Serializable
data class SearchHit(
    @SerialName("session_id") val sessionId: String = "",
    val title: String? = null,
    val snippet: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val role: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
)

@Serializable
data class SearchResponse(val results: List<SearchHit> = emptyList())

@Serializable
data class SkillInfo(
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val category: String? = null,
    val provenance: String? = null,
    val locked: Boolean = false,
)

@Serializable
data class SkillContent(val content: String = "", val name: String = "", val path: String = "")

@Serializable
data class ToggleRequest(val name: String, val enabled: Boolean)

@Serializable
data class CronSchedule(val kind: String? = null, val expr: String? = null, val minutes: Int? = null, val display: String? = null)

@Serializable
data class CronExecution(val id: String? = null, val status: String? = null, val error: String? = null, @SerialName("claimed_at") val claimedAt: String? = null)

@Serializable
data class CronJob(
    val id: String,
    val name: String? = null,
    val prompt: String? = null,
    val script: String? = null,
    val schedule: CronSchedule? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean = false,
    val state: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("failure_streak") val failureStreak: Int = 0,
    val deliver: String? = null,
    val model: String? = null,
    @SerialName("latest_execution") val latestExecution: CronExecution? = null,
)

@Serializable
data class CronJobUpdate(val enabled: Boolean? = null, val name: String? = null, val prompt: String? = null, val schedule: String? = null, val deliver: String? = null)

@Serializable
data class CronUpdateBody(val updates: CronJobUpdate)

@Serializable
data class CronCreateBody(val name: String? = null, val prompt: String, val schedule: String, val deliver: String? = null, val enabled: Boolean = true)

@Serializable
data class CronRuns(val runs: List<SessionInfo> = emptyList())

@Serializable
data class OkResponse(val ok: Boolean = false, val title: String? = null)

@Serializable
data class MemRecord(
    val id: Long,
    val text: String,
    val category: String = "other",
    val importance: Int = 3,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("learned_at") val learnedAt: String? = null,
    @SerialName("valid_from") val validFrom: String? = null,
    @SerialName("valid_to") val validTo: String? = null,
    val confidence: Double = 1.0,
    val source: String = "",
    val status: String = "active",
    @SerialName("replaces_id") val replacesId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("semantic_similarity") val semanticSimilarity: Double? = null,
)

/** Gateway events, delivered by the WebSocket. Payload stays raw for per-type readers. */
data class GatewayEvent(val type: String, val sessionId: String, val payload: JsonObject) {
    fun str(key: String): String = payload[key]?.let {
        when (it) {
            is kotlinx.serialization.json.JsonNull -> ""
            is kotlinx.serialization.json.JsonPrimitive -> it.content
            else -> it.toString()
        }
    } ?: ""
}

enum class SocketState { DISCONNECTED, CONNECTING, CONNECTED, LOGIN_FAILED }

class ApiError(val code: Int, message: String) : Exception(message)

/** Lenient JSON: the backend adds fields freely across versions. */
val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
