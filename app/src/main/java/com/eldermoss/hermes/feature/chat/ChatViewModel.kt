package com.eldermoss.hermes.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.net.ApiError
import com.eldermoss.hermes.core.net.GatewayEvent
import com.eldermoss.hermes.core.net.SocketState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface ChatItem {
    data class Text(val role: String, var text: String) : ChatItem
    data class Thinking(var text: String) : ChatItem
    data class Tool(val name: String, val toolId: String, var result: String? = null) : ChatItem
}

data class ApprovalPrompt(val requestId: String, val command: String, val description: String, val choices: List<String>)
data class ClarifyPrompt(val requestId: String, val question: String, val choices: List<String>)

data class ChatUi(
    val storedId: String? = null,
    val runtimeId: String? = null,
    val title: String = "New chat",
    val items: List<ChatItem> = emptyList(),
    val busy: Boolean = false,
    val approval: ApprovalPrompt? = null,
    val clarify: ClarifyPrompt? = null,
    val error: String? = null,
) {
    /** Last assistant text block, for list previews. */
    val lastAssistant: String?
        get() = items.filterIsInstance<ChatItem.Text>().lastOrNull { it.role == "assistant" }?.text
}

class ChatViewModel : ViewModel() {

    private val connections = ServiceLocator.connections
    private val socket = connections.socket

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** Session ids this VM reacts to (runtime id while live; stored id for titles). */
    private var storedId: String? = null
    private var runtimeId: String? = null
    private var eventsJob: Job? = null

    init {
        socket.start()
        observeEvents()
    }

    fun openExisting(sessionId: String) {
        if (sessionId == storedId && _ui.value.items.isNotEmpty()) return
        storedId = sessionId
        runtimeId = null
        _ui.value = ChatUi(storedId = sessionId, title = "Loading…")
        viewModelScope.launch {
            try {
                val result = socket.rpc("session.resume", buildJsonObject { put("session_id", sessionId) })
                val obj = result as? kotlinx.serialization.json.JsonObject
                val runtime = obj?.get("session_id")?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
                val msgs = obj?.get("messages") as? kotlinx.serialization.json.JsonArray
                val items = ArrayList<ChatItem>()
                msgs?.forEach { m ->
                    val o = m as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    val role = o.str("role")
                    val text = o.str("text").ifBlank { o.str("content") }
                    when {
                        role == "tool" -> items.add(ChatItem.Tool(o.str("name").ifBlank { "tool" }, text.take(24), text))
                        text.isNotBlank() -> items.add(ChatItem.Text(role, text))
                    }
                }
                runtimeId = runtime
                _ui.value = _ui.value.copy(runtimeId = runtime, items = items, title = _ui.value.title)
                refreshTitle()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(title = "Session", error = friendly(e))
            }
        }
    }

    private suspend fun refreshTitle() {
        val id = storedId ?: return
        try {
            val page = connections.sessions.list(limit = 100)
            page.sessions.firstOrNull { it.id == id }?.let { row ->
                _ui.value = _ui.value.copy(title = row.title ?: id)
            }
        } catch (_: Exception) { }
    }

    fun newSession() {
        storedId = null
        runtimeId = null
        _ui.value = ChatUi()
        viewModelScope.launch {
            try {
                val result = socket.rpc("session.create")
                val obj = result as? kotlinx.serialization.json.JsonObject
                storedId = obj.str("stored_session_id").ifBlank { null }
                runtimeId = obj.str("session_id").ifBlank { null }
                _ui.value = _ui.value.copy(storedId = storedId, runtimeId = runtimeId)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = friendly(e))
            }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _ui.value.busy) return
        val rid = runtimeId
        if (rid == null) {
            // No live session yet: create one, then submit.
            _ui.value = _ui.value.copy(items = _ui.value.items + ChatItem.Text("user", clean), busy = true)
            viewModelScope.launch {
                try {
                    val created = socket.rpc("session.create")
                    val obj = created as? kotlinx.serialization.json.JsonObject
                    storedId = obj.str("stored_session_id").ifBlank { null } ?: storedId
                    val newRid = obj.str("session_id").ifBlank { null }
                    if (newRid == null) {
                        _ui.value = _ui.value.copy(busy = false, error = "Server returned no session id")
                        return@launch
                    }
                    runtimeId = newRid
                    _ui.value = _ui.value.copy(storedId = storedId, runtimeId = runtimeId)
                    submit(newRid, clean)
                } catch (e: Exception) {
                    _ui.value = _ui.value.copy(busy = false, error = friendly(e))
                }
            }
            return
        }
        _ui.value = _ui.value.copy(items = _ui.value.items + ChatItem.Text("user", clean), busy = true)
        viewModelScope.launch { submit(rid, clean) }
    }

    private suspend fun submit(rid: String, text: String) {
        try {
            socket.rpc(
                "prompt.submit",
                buildJsonObject { put("session_id", rid); put("text", text) },
                timeoutMs = 1_800_000,
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = friendly(e))
        }
    }

    fun interrupt() {
        val rid = runtimeId ?: return
        viewModelScope.launch {
            runCatching {
                socket.rpc("session.interrupt", buildJsonObject { put("session_id", rid) })
            }.onFailure { err -> _ui.value = _ui.value.copy(error = friendly(err)) }
        }
    }

    fun respondApproval(choice: String) {
        val prompt = _ui.value.approval ?: return
        _ui.value = _ui.value.copy(approval = null)
        respond("approval.respond", prompt.requestId, choice)
    }

    fun respondClarify(answer: String) {
        val prompt = _ui.value.clarify ?: return
        _ui.value = _ui.value.copy(clarify = null)
        respond("clarify.respond", prompt.requestId, answer)
    }

    private fun respond(method: String, requestId: String, value: String) {
        val rid = runtimeId ?: return
        viewModelScope.launch {
            runCatching {
                socket.rpc(method, buildJsonObject {
                    put("session_id", rid); put("request_id", requestId); put(if (method == "approval.respond") "choice" else "answer", value)
                })
            }.onFailure { err -> _ui.value = _ui.value.copy(error = friendly(err)) }
        }
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }

    // ---- event stream ----

    private fun observeEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            socket.events.collect { ev -> onEvent(ev) }
        }
    }

    private fun onEvent(ev: GatewayEvent) {
        val activeRuntime = runtimeId
        val matchesSession = activeRuntime == null || ev.sessionId.isBlank() || ev.sessionId == activeRuntime || ev.sessionId == storedId
        if (!matchesSession) return
        val items = _ui.value.items
        when (ev.type) {
            "thinking.delta" -> {
                val mutable = items.toMutableList()
                val idx = mutable.indexOfLast { it is ChatItem.Thinking }
                val delta = ev.str("text")
                if (idx >= 0) {
                    val t = mutable[idx] as ChatItem.Thinking
                    mutable[idx] = ChatItem.Thinking(t.text + delta)
                } else {
                    mutable.add(ChatItem.Thinking(delta))
                }
                _ui.value = _ui.value.copy(items = mutable)
            }
            "message.delta" -> {
                // A real answer started: drop any trailing thinking bubble.
                val mutable = items.toMutableList()
                val lastThinking = mutable.indexOfLast { it is ChatItem.Thinking }
                if (lastThinking == mutable.lastIndex) mutable.removeAt(lastThinking)
                val delta = ev.str("text")
                val last = mutable.lastOrNull()
                if (last is ChatItem.Text && last.role == "assistant") {
                    mutable[mutable.lastIndex] = ChatItem.Text("assistant", last.text + delta)
                } else {
                    mutable.add(ChatItem.Text("assistant", delta))
                }
                _ui.value = _ui.value.copy(items = mutable)
            }
            "message.complete" -> {
                val complete = ev.str("text")
                val mutable = items.toMutableList()
                val lastThinking = mutable.indexOfLast { it is ChatItem.Thinking }
                if (lastThinking == mutable.lastIndex) mutable.removeAt(lastThinking)
                val last = mutable.lastOrNull()
                if (complete.isNotBlank()) {
                    if (last is ChatItem.Text && last.role == "assistant") {
                        mutable[mutable.lastIndex] = ChatItem.Text("assistant", complete)
                    } else {
                        mutable.add(ChatItem.Text("assistant", complete))
                    }
                }
                _ui.value = _ui.value.copy(items = mutable, busy = false)
            }
            "tool.start" -> {
                val mutable = items.toMutableList()
                mutable.add(ChatItem.Tool(ev.str("name").ifBlank { "tool" }, ev.str("tool_id")))
                _ui.value = _ui.value.copy(items = mutable)
            }
            "tool.complete" -> {
                val mutable = items.toMutableList()
                val tid = ev.str("tool_id")
                val idx = mutable.indexOfLast { it is ChatItem.Tool && (it as ChatItem.Tool).toolId == tid }
                if (idx >= 0) {
                    val t = mutable[idx] as ChatItem.Tool
                    mutable[idx] = ChatItem.Tool(t.name, t.toolId, ev.str("result"))
                } else {
                    mutable.add(ChatItem.Tool(ev.str("name").ifBlank { "tool" }, tid, ev.str("result")))
                }
                _ui.value = _ui.value.copy(items = mutable)
            }
            "session.title" -> {
                val sid = ev.str("session_id")
                if (sid == storedId) _ui.value = _ui.value.copy(title = ev.str("title"))
            }
            "approval.request" -> {
                val choices = ev.payload["choices"]?.let { arr ->
                    (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                } ?: listOf("Allow", "Deny")
                _ui.value = _ui.value.copy(approval = ApprovalPrompt(
                    requestId = ev.str("prompt_id").ifBlank { ev.str("request_id") },
                    command = ev.str("command"),
                    description = ev.str("description"),
                    choices = choices,
                ))
            }
            "clarify.request" -> {
                val q = ev.payload["questions"] as? kotlinx.serialization.json.JsonArray
                val first = q?.firstOrNull() as? kotlinx.serialization.json.JsonObject
                val choices = first?.get("choices")?.let { arr ->
                    (arr as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }
                }
                _ui.value = _ui.value.copy(clarify = ClarifyPrompt(
                    requestId = first.str("qid").ifBlank { ev.str("prompt_id") },
                    question = first.str("question").ifBlank { ev.str("question") },
                    choices = choices ?: emptyList(),
                ))
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObject?.str(key: String): String {
        val v = this?.get(key) ?: return ""
        return (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
    }

    fun friendly(e: Throwable): String = when (e) {
        is ApiError -> if (e.code == 0 && e.message?.contains("Not connected") == true)
            "Offline - reconnecting…" else e.message ?: "Error"
        else -> e.message ?: "Error"
    }

    override fun onCleared() {
        // Keep the socket alive app-wide; nothing to release here.
    }

    val socketState: StateFlow<SocketState> get() = socket.state
    val socketDetail: StateFlow<String> get() = socket.detail

    fun reconnect() {
        socket.stop()
        socket.start()
    }
}
