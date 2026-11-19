package com.eldermoss.hermes.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC 2.0 over the gateway WebSocket, with automatic reconnect and
 * backoff. Wire contract (verified against the desktop app source and live):
 *
 *   1. cookie login (HermesClient)
 *   2. POST /api/auth/ws-ticket -> one-use 30s ticket
 *   3. WS <base>/api/ws?ticket=<t>
 *   4. frames: {"jsonrpc":"2.0","id":N,"method":M,"params":P}
 *      replies: {"jsonrpc":"2.0","id":N,"result"|"error"}
 *      events:  {"jsonrpc":"2.0","method":"event","params":{type,session_id,payload,seq}}
 */
class GatewaySocket(private val client: HermesClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WS lives on readTimeout(0)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
    private val connectMutex = Mutex()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wantConnected = false
    private val loopLock = Any()
    @Volatile private var loop: Job? = null

    /** Starts (or keeps) the connection with reconnect-on-drop. Idempotent. */
    fun start() {
        wantConnected = true
        synchronized(loopLock) {
            if (loop?.isActive == true) return
            loop = scope.launch { runLoop() }
        }
    }

    fun stop() {
        wantConnected = false
        loop?.cancel()
        loop = null
        ws?.close(1000, "bye")
        ws = null
        _state.value = SocketState.DISCONNECTED
    }

    private suspend fun runLoop() {
        val backoff = Backoff()
        while (wantConnected) {
            val opened = connectMutex.withLock { dial() }
            if (opened) {
                backoff.reset()
                // Wait until the socket dies, then back off and redial.
                while (wantConnected && ws != null) delay(500)
            } else {
                _state.value = SocketState.DISCONNECTED
            }
            if (!wantConnected) break
            delay(backoff.next())
        }
    }

    /** login -> ticket -> dial. Returns true when the socket opened. */
    private suspend fun dial(): Boolean {
        if (ws != null) return true
        _state.value = SocketState.CONNECTING
        if (client.currentCookie() == null) {
            val err = client.login()
            if (err != null) {
                _state.value = SocketState.LOGIN_FAILED
                return false
            }
        }
        val ticket = try {
            client.wsTicket()
        } catch (e: ApiError) {
            if (e.code == 401) {
                client.setCookie(null)
                val err = client.login()
                if (err != null) { _state.value = SocketState.LOGIN_FAILED; return false }
                try { client.wsTicket() } catch (_: Exception) { return false }
            } else return false
        } catch (_: Exception) { return false }

        val req = Request.Builder().url("${client.baseUrl()}/api/ws?ticket=$ticket").build()
        val opened = CompletableDeferred<Boolean>()
        val socket = wsHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
                _state.value = SocketState.CONNECTED
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failPending("Connection dropped: ${t.message ?: t.javaClass.simpleName}")
                ws = null
                opened.complete(false)
                _state.value = SocketState.DISCONNECTED
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failPending("Connection closed ($code)")
                ws = null
                _state.value = SocketState.DISCONNECTED
            }
            override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)
        })
        val ok = runCatching { withTimeout(20_000) { opened.await() } }.getOrDefault(false)
        if (ok) ws = socket else socket.cancel()
        return ok
    }

    private fun onFrame(text: String) {
        val frame = runCatching { lenientJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        val id = (frame["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: -1L
        if (id >= 0 && (frame.containsKey("result") || frame.containsKey("error"))) {
            val d = pending.remove(id) ?: return
            val error = frame["error"]
            if (error != null) {
                val msg = (error as? JsonObject)?.get("message")?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: "RPC error"
                d.completeExceptionally(ApiError(0, msg))
            } else {
                d.complete(frame["result"])
            }
            return
        }
        if ((frame["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "event") {
            val params = frame["params"] as? JsonObject ?: return
            val type = (params["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
            val sid = (params["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
            _events.tryEmit(GatewayEvent(type, sid, payload))
        }
    }

    private fun failPending(reason: String) {
        for ((_, d) in pending) d.completeExceptionally(ApiError(0, reason))
        pending.clear()
    }

    /** Suspends until the RPC answers. Throws on error/timeout/disconnect. */
    suspend fun rpc(method: String, params: JsonObject = buildJsonObject { }, timeoutMs: Long = 30_000): JsonElement {
        val socket = ws ?: throw ApiError(0, "Not connected")
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        if (!socket.send(frame)) {
            pending.remove(id)
            throw ApiError(0, "Connection dropped")
        }
        try {
            return withTimeout(timeoutMs) { deferred.await() }
                ?: throw ApiError(0, "Empty response")
        } finally {
            pending.remove(id)
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
