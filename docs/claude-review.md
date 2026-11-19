# Claude Sonnet 4.6 code review (44848 in / 8000 out tokens)

# Hermes Android Client — Senior Engineer Review

---

## Priority 1 — Concurrency / Coroutine Bugs

### 1.1 `MemRepository.fetch()` blocks a coroutine thread (BLOCKING I/O on wrong dispatcher)

**File:** `Repositories.kt`, `MemRepository.fetch()`

`fetch()` is a plain `fun` (not `suspend`) that calls `http.newCall(...).execute()` — a blocking OkHttp call. It is invoked from `suspend` functions (`listCall`, `raw`) which are called from `viewModelScope.launch {}` (which defaults to `Dispatchers.Main.immediate` for the launch, then the coroutine body runs on whatever dispatcher the ViewModel scope uses — `Dispatchers.Main` by default).

Even if the caller happens to be on `Dispatchers.IO` (it isn't — `MemoryViewModel` uses `viewModelScope` which is `Dispatchers.Main`), the function signature gives no contract. The `HermesClient` correctly wraps everything in `withContext(Dispatchers.IO)`. `MemRepository` does not.

**Fix:** Either make `fetch` a `suspend fun` and wrap the blocking call in `withContext(Dispatchers.IO) { ... }`, or annotate it `@WorkerThread` and ensure every call site dispatches correctly. The former is cleaner:

```kotlin
private suspend fun fetch(method: String, path: String, bodyJson: String?): String =
    withContext(Dispatchers.IO) {
        // ... existing blocking code ...
    }
```

---

### 1.2 Race between `stop()` and `runLoop()` — `ws` set to null non-atomically

**File:** `GatewaySocket.kt`, `stop()` and `dial()`

`stop()` does:
```kotlin
loop?.cancel()
loop = null
ws?.close(1000, "bye")
ws = null
_state.value = SocketState.DISCONNECTED
```

`dial()` checks `if (ws != null) return true` and later sets `ws = socket`. Both `ws` reads/writes are `@Volatile` which only guarantees visibility, not atomicity of check-then-act. The sequence:

1. `runLoop` coroutine: `dial()` checks `ws == null` → true, proceeds
2. `stop()` on another thread: sets `ws = null`, cancels loop
3. `dial()` completes, sets `ws = socket` — now `ws` is non-null but `loop` is cancelled and `wantConnected = false`
4. The socket is open but nothing drives it; `ws` leaks

The `connectMutex` only serialises concurrent `dial()` calls, not `stop()` vs `dial()`.

**Fix:** Check `wantConnected` inside `connectMutex.withLock` in `dial()`, and have `stop()` also acquire the mutex before nulling `ws`:

```kotlin
private suspend fun dial(): Boolean = connectMutex.withLock {
    if (!wantConnected) return false
    if (ws != null) return true
    // ... rest of dial
}
```

And in `stop()`:
```kotlin
fun stop() {
    wantConnected = false
    loop?.cancel()
    loop = null
    // ws close must happen after loop is cancelled; use runBlocking or a dedicated cleanup coroutine
    ws?.close(1000, "bye")
    ws = null
    _state.value = SocketState.DISCONNECTED
}
```

---

### 1.3 `runLoop` busy-polls with `ws != null` — misses the `onClosed`/`onFailure` window

**File:** `GatewaySocket.kt`, `runLoop()`

```kotlin
while (wantConnected && ws != null) delay(500)
```

`onClosed` and `onFailure` both set `ws = null`. But between the OkHttp callback thread setting `ws = null` and the coroutine waking from `delay(500)`, up to 500 ms passes. During that window, `_state` is already `DISCONNECTED` but the loop hasn't re-entered `dial()` yet. That's cosmetic. The real problem: if `stop()` is called while the loop is in `delay(500)`, the `loop?.cancel()` cancels the coroutine, but `ws` is still non-null (the socket is open). `stop()` then closes it — fine. But if `stop()` races with `onClosed` setting `ws = null` first, `stop()` calls `ws?.close()` on null — harmless but indicative of the fragility.

**Better approach:** Use a `CompletableDeferred<Unit>` or a `Channel` that `onClosed`/`onFailure` complete, and `await()` it instead of polling:

```kotlin
val disconnected = CompletableDeferred<Unit>()
// pass into WebSocketListener, complete on onClosed/onFailure
disconnected.await() // replaces the polling loop
```

---

### 1.4 `pending` map leaks on `stop()` if `rpc()` is mid-flight

**File:** `GatewaySocket.kt`, `stop()` and `rpc()`

`stop()` calls `ws?.close(1000, "bye")` which triggers `onClosed` → `failPending()`. But `stop()` also sets `ws = null` before `onClosed` fires (OkHttp calls `onClosed` asynchronously on its dispatcher). If `rpc()` is suspended in `withTimeout { deferred.await() }` and `stop()` runs, the sequence can be:

1. `stop()`: `ws = null`
2. `rpc()`: `withTimeout` expires → `TimeoutCancellationException` thrown
3. `finally { pending.remove(id) }` — removes the entry
4. `onClosed` fires → `failPending()` — entry already gone, no double-complete

Actually this path is safe. The real leak: if `stop()` is called and `onClosed` never fires (e.g., the socket was already half-open / TCP RST), `pending` entries remain forever. `failPending()` should also be called directly in `stop()`:

```kotlin
fun stop() {
    wantConnected = false
    loop?.cancel()
    loop = null
    failPending("Socket stopped")  // add this
    ws?.close(1000, "bye")
    ws = null
    _state.value = SocketState.DISCONNECTED
}
```

---

### 1.5 `ConnectionManager.init` collects settings on `Dispatchers.IO` but calls `socket.stop()` which is not thread-safe

**File:** `ConnectionManager.kt`, `init` block

`scope` uses `Dispatchers.IO`. The `settings.settings.collect` lambda calls `socket.stop()` and `socket.start()`. `GatewaySocket.stop()` writes `_state.value` (a `MutableStateFlow`) and cancels a `Job` — both are fine from any thread. But `socket.start()` does `synchronized(loopLock)` and launches a coroutine — also fine. However, `client.setCookie(null)` is a `@Volatile` write — fine. No hard bug here, but the mixing of `@Volatile` fields, `synchronized` blocks, and coroutine primitives across threads is fragile. Document the threading contract or consolidate to a single-threaded dispatcher for the manager.

---

### 1.6 `Notifier.agentFinished` calls `runBlocking` on the OkHttp callback thread

**File:** `Notifier.kt`, `agentFinished()`

```kotlin
val settings = kotlinx.coroutines.runBlocking { ServiceLocator.settings.snapshot() }
```

`agentFinished` is called from `LinkService`'s `scope.launch { socket.events.collect { ... } }` which runs on `Dispatchers.IO`. `runBlocking` on an IO thread is legal but blocks that thread for the duration of a DataStore read. Under load (many events) this can exhaust the IO thread pool. 

**Fix:** Make `agentFinished` a `suspend fun` and call it from the existing coroutine:

```kotlin
suspend fun agentFinished(context: Context, sessionId: String, text: String) {
    val settings = ServiceLocator.settings.snapshot()
    // ...
}
```

---

### 1.7 `ChatViewModel.send()` — race between `newSession()` and `send()` on `runtimeId`

**File:** `ChatViewModel.kt`, `send()`

```kotlin
val rid = runtimeId
if (rid == null) {
    viewModelScope.launch {
        val created = socket.rpc("session.create")
        runtimeId = obj.str("session_id").ifBlank { null }
        submit(runtimeId!!, clean)  // ← NPE if rpc returned blank session_id
    }
    return
}
```

If `session.create` returns a blank `session_id` (server bug or protocol mismatch), `runtimeId` is null and `runtimeId!!` throws `NullPointerException` — uncaught, crashes the coroutine silently (swallowed by `viewModelScope`). The `try/catch` only catches `Exception`, not `NullPointerException`... actually NPE is an `Exception` subclass, so it is caught. But the error message will be confusing. More importantly: if the user taps Send twice rapidly while `rid == null`, two `session.create` RPCs fire concurrently, creating two sessions. The second `submit` wins the `runtimeId` write.

**Fix:** Set `busy = true` before the null check (already done) and guard with a flag, or check `busy` at the top of `send()` (already done for the non-null path but the null path also sets `busy = true` — actually it does, so double-send is blocked). The NPE risk remains; add an explicit null check:

```kotlin
val newRid = obj.str("session_id").ifBlank { null }
    ?: run { _ui.value = _ui.value.copy(busy = false, error = "Server returned no session ID"); return@launch }
runtimeId = newRid
submit(newRid, clean)
```

---

## Priority 2 — WebSocket Reconnect Logic (GatewaySocket)

### 2.1 Ticket expiry during slow dial — no retry

**File:** `GatewaySocket.kt`, `dial()`

The WS ticket has a 30-second TTL (per DESIGN.md). `dial()` fetches the ticket, then calls `wsHttp.newWebSocket(req, listener)` and waits up to 20 seconds for `onOpen`. If the device is on a slow tailnet link and the TCP handshake + TLS takes > 30 seconds (unlikely but possible on a cold Tailscale path), the server will reject the ticket with a 401/403 on the upgrade response. OkHttp will call `onFailure`, `opened.complete(false)`, and `dial()` returns false. The backoff loop will retry — fetching a fresh ticket — so this self-heals. **Not a hard bug**, but the error is silent (no log, no distinct state). Consider setting `_state.value = SocketState.CONNECTING` with a sub-state or logging.

### 2.2 Half-open detection relies entirely on OkHttp ping/pong

**File:** `GatewaySocket.kt`, constructor

```kotlin
.pingInterval(25, TimeUnit.SECONDS)
```

OkHttp sends WebSocket pings every 25 seconds. If the server doesn't respond within OkHttp's internal timeout (which is the `readTimeout` — set to 0, meaning infinite), a half-open connection will never be detected. OkHttp's WebSocket implementation does close the connection if a ping goes unanswered, but only after the *next* ping interval. So a half-open can persist for up to 50 seconds before detection. For a chat app this is acceptable, but document it.

More critically: `readTimeout(0)` means OkHttp will wait forever for any read. If the server sends a partial frame and stalls, the socket hangs indefinitely. The ping mechanism only fires if OkHttp's event loop is running, which requires the socket to be in a readable state. A TCP-level half-open (ACK but no data) will be caught by ping. A stalled TLS layer may not be.

**Fix:** Consider adding an application-level heartbeat in `runLoop` that sends a JSON-RPC ping and expects a response within N seconds, closing the socket if it doesn't arrive.

### 2.3 `onOpen` called before `ws = socket` assignment — window where `rpc()` sees `ws == null`

**File:** `GatewaySocket.kt`, `dial()`

```kotlin
val socket = wsHttp.newWebSocket(req, object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(true)
        _state.value = SocketState.CONNECTED  // ← state says CONNECTED
    }
    // ...
})
val ok = runCatching { withTimeout(20_000) { opened.await() } }.getOrDefault(false)
if (ok) ws = socket  // ← ws assigned AFTER state is CONNECTED
```

Between `_state.value = SocketState.CONNECTED` and `ws = socket`, any observer of `state` that immediately calls `rpc()` will find `ws == null` and throw `ApiError(0, "Not connected")`. This is a real race: `ChatViewModel.observeEvents()` collects `socket.events` (not `state`), so it's not affected. But any code that does `if (state == CONNECTED) socket.rpc(...)` will hit this.

**Fix:** Assign `ws = socket` before completing `opened`, or set `_state.value = SocketState.CONNECTED` after `ws = socket`:

```kotlin
val ok = runCatching { withTimeout(20_000) { opened.await() } }.getOrDefault(false)
if (ok) {
    ws = socket
    _state.value = SocketState.CONNECTED  // move here, after ws is set
} else {
    socket.cancel()
}
```

And remove `_state.value = SocketState.CONNECTED` from `onOpen`.

### 2.4 `wantConnected` is a plain `@Volatile Boolean` — not a coroutine-safe cancellation signal

**File:** `GatewaySocket.kt`

`runLoop` checks `while (wantConnected)`. `stop()` sets `wantConnected = false` and cancels `loop`. The cancellation of `loop` will throw `CancellationException` at the next suspension point (`delay`, `connectMutex.withLock`, `opened.await`). So `wantConnected` is redundant with coroutine cancellation — but it's also checked in the `while` condition before any suspension, which is fine. The issue: after `loop?.cancel()`, the coroutine may still be running (cancellation is cooperative). `stop()` then sets `ws = null` and calls `ws?.close()`. If the coroutine is between `dial()` returning `true` and the `while (wantConnected && ws != null)` check, it sees `ws == null` and exits the inner loop, then checks `wantConnected == false` and exits the outer loop — correct. No hard bug, but the dual mechanism is confusing.

### 2.5 Login retry in `dial()` does not re-fetch the ticket after re-login

**File:** `GatewaySocket.kt`, `dial()`

```kotlin
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
```

The outer `try` catches `ApiError` with code 401 on `wsTicket()`. It re-logs in and calls `wsTicket()` again — correct. But the inner `try { client.wsTicket() }` result is discarded (the `catch` returns false, but the success path falls through to... nothing — the `ticket` variable is never assigned from the inner call). This is a Kotlin control flow bug: the `try` expression result is not assigned to `ticket`.

**Actual behavior:** In Kotlin, `val ticket = try { ... } catch (e: ApiError) { if (...) { ...; try { client.wsTicket() } catch ... } else return false } catch ...` — the inner `try { client.wsTicket() }` is the last expression of the `catch (e: ApiError)` block, so its value IS returned as the value of the outer `try` expression and assigned to `ticket`. Let me re-read...

Actually yes — in Kotlin, the `catch` block's last expression is the value of the `try` expression when that branch is taken. The inner `try { client.wsTicket() } catch (_: Exception) { return false }` — if the inner `wsTicket()` succeeds, it returns the ticket string, which becomes the value of the outer `catch` block, which becomes the value of `val ticket = try { ... }`. **This is correct.** Not a bug — but extremely hard to read. A refactor into a helper function would prevent future misreads.

---

## Priority 3 — Compose Issues

### 3.1 `ChatScreen` — `LaunchedEffect(initialSessionId)` with `started` guard is fragile

**File:** `ChatScreen.kt`

```kotlin
var started by remember { mutableStateOf(false) }
LaunchedEffect(initialSessionId) {
    if (!started) {
        started = true
        if (initialSessionId != null) vm.openExisting(initialSessionId) else vm.newSession()
    }
}
```

`LaunchedEffect(initialSessionId)` already re-runs when `initialSessionId` changes. The `started` guard prevents it from running again on recomposition with the same key — but `LaunchedEffect` already guarantees that. The `started` flag is redundant and creates a subtle bug: if `initialSessionId` changes from `"abc"` to `null` (navigating to a new chat), `LaunchedEffect` re-runs (key changed), but `started` is still `true`, so `vm.newSession()` is never called.

**Fix:** Remove `started` entirely:

```kotlin
LaunchedEffect(initialSessionId) {
    if (initialSessionId != null) vm.openExisting(initialSessionId) else vm.newSession()
}
```

### 3.2 `ChatScreen` — scroll-to-bottom `LaunchedEffect` uses two keys that can diverge

**File:** `ChatScreen.kt`

```kotlin
LaunchedEffect(ui.items.size, ui.items.lastOrNull()) {
    if (ui.items.isNotEmpty()) listState.animateScrollToItem(ui.items.lastIndex)
}
```

`ui.items.lastOrNull()` on a list of sealed interface items uses the default `equals()`. `ChatItem.Text` is a `data class` — equality by value. If the last item's text changes (streaming delta), `lastOrNull()` returns a new object with different content, triggering a scroll. This means every streaming delta causes an `animateScrollToItem` call, which interrupts any ongoing animation and restarts it. On fast streaming this creates a jittery scroll.

**Fix:** Key only on `ui.items.size`:

```kotlin
LaunchedEffect(ui.items.size) {
    if (ui.items.isNotEmpty()) listState.animateScrollToItem(ui.items.lastIndex)
}
```

### 3.3 `SchedulesScreen` editor dialog — `remember` without key loses state on recomposition

**File:** `SchedulesScreen.kt`

```kotlin
ui.editor?.let { editor ->
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(editor.name) }
    var schedule by androidx.compose.runtime.remember { ... }
    var prompt by androidx.compose.runtime.remember { ... }
```

`remember { mutableStateOf(editor.name) }` without a key captures `editor.name` at first composition only. If `ui.editor` changes (e.g., the user opens a different job's editor), the `remember` block does not re-run — `name`, `schedule`, `prompt` retain the previous job's values. The dialog will show stale data.

**Fix:** Key on `editor.jobId`:

```kotlin
var name by remember(editor.jobId) { mutableStateOf(editor.name) }
var schedule by remember(editor.jobId) { mutableStateOf(editor.schedule) }
var prompt by remember(editor.jobId) { mutableStateOf(editor.prompt) }
```

### 3.4 `SessionsScreen` — `renameTarget`/`deleteTarget` are plain `var` on ViewModel, not `StateFlow`

**File:** `SessionsScreen.kt`, `SessionsViewModel`

```kotlin
var renameTarget: SessionInfo? = null
var deleteTarget: SessionInfo? = null
```

These are mutable fields on the ViewModel, not observed state. The Composable reads them directly:

```kotlin
val t = vm.renameTarget
```

This is not reactive — Compose will not recompose when these change. The dialog open/close is driven by `renameOpen`/`deleteOpen` local `remember` state, which is set in the same click handler that sets `vm.renameTarget`. This works as long as the click handler and the dialog read happen in the same recomposition. But if the ViewModel is shared (it's not here, but the pattern is dangerous) or if a configuration change happens between setting `renameTarget` and reading it in the dialog, the target will be stale or null.

**Fix:** Move `renameTarget` and `deleteTarget` into `SessionsUi` as nullable fields, driven by `_ui.value = _ui.value.copy(renameTarget = row)`.

### 3.5 `AppRoot` — `getCredentials()` called synchronously on the main thread during composition

**File:** `AppRoot.kt`

```kotlin
val configured = ServiceLocator.settings.getCredentials().second.isNotBlank()
```

`getCredentials()` reads from `EncryptedSharedPreferences`, which involves Keystore operations. On first access after boot (before the key is in the Keystore cache), this can take 50–200 ms on some devices, blocking the main thread during composition. It's called on every recomposition of `AppRoot`.

**Fix:** Cache the result in a `StateFlow` in `SettingsRepository`, or use `produceState` / `collectAsStateWithLifecycle` to observe it asynchronously.

---

## Priority 4 — Protocol Mismatches

### 4.1 `session.resume` response parsing assumes `messages[].text` field — not in the documented contract

**File:** `ChatViewModel.kt`, `openExisting()`

```kotlin
val text = o.str("text").ifBlank { o.str("content") }
```

DESIGN.md documents `SessionMessage` with a `content` field (matching the `@SerialName("content")` in `Models.kt`). The code tries `text` first, then falls back to `content`. If the server only sends `content`, the fallback works. But if the server sends neither (e.g., a tool message with only `tool_call_id`), both are blank and the message is silently dropped. This is probably intentional for tool messages, but the `role == "tool"` branch checks `o.str("name")` which also may be blank for some tool messages.

### 4.2 `approval.respond` uses `request_id` but `approval.request` event uses `prompt_id`

**File:** `ChatViewModel.kt`

```kotlin
// In onEvent, approval.request:
requestId = ev.str("prompt_id").ifBlank { ev.str("request_id") },

// In respond():
put("request_id", requestId)
```

The code defensively reads both `prompt_id` and `request_id` from the event. But the RPC call always sends `request_id`. DESIGN.md says `approval.respond {session_id, request_id, choice}` — so `request_id` is correct for the outbound call. The inbound event field name ambiguity (`prompt_id` vs `request_id`) is handled by the fallback. This is fine but fragile — if the server uses `prompt_id` exclusively and the fallback `request_id` is blank, the stored `requestId` will be the `prompt_id` value, which is then sent as `request_id` in the response. Whether the server accepts this depends on its implementation. **Verify against the live server.**

### 4.3 `CronRepository.update()` wraps in `CronUpdateBody` but the API contract shows `{updates:{...}}`

**File:** `Repositories.kt`, `CronRepository.update()`

```kotlin
suspend fun update(jobId: String, updates: CronJobUpdate): CronJob =
    decode(client.call("PUT", "/api/cron/jobs/$jobId", toJson(CronUpdateBody(updates))))
```

`CronUpdateBody` is `data class CronUpdateBody(val updates: CronJobUpdate)` — serializes to `{"updates":{...}}`. DESIGN.md confirms `PUT /api/cron/jobs/{id}` takes `{updates:{enabled, prompt, schedule, ...}}`. This matches. ✓

### 4.4 `MessagesPage` order guard not implemented

DESIGN.md notes: "order-echo guard: only trust a page that echoes `order:latest` as the tail". The client requests `order=latest` but never checks the `pagination.order` field in the response. If the server returns messages in a different order (e.g., due to a server-side bug or version mismatch), the chat history will display incorrectly.

### 4.5 `wsTicket()` trims quotes from JSON string incorrectly

**File:** `HermesClient.kt`

```kotlin
return obj["ticket"]?.toString()?.trim('"') ?: throw ApiError(500, "ws-ticket: missing ticket")
```

`JsonElement.toString()` on a `JsonPrimitive` string returns the JSON-encoded form including quotes: `"\"abc123\""`. `trim('"')` removes leading/trailing `"` characters — correct for a simple string. But if the ticket contains a `"` character (unlikely for a UUID/token but possible), `trim('"')` would corrupt it. Use `JsonPrimitive.content` instead:

```kotlin
return (obj["ticket"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    ?: throw ApiError(500, "ws-ticket: missing ticket")
```

The same pattern is used in `probe()` for `version` and `gateway_running` — same risk.

---

## Priority 5 — Security Issues

### 5.1 Password displayed in plaintext in `SettingsScreen`

**File:** `SettingsScreen.kt`

```kotlin
OutlinedTextField(
    value = password, onValueChange = { password = it },
    label = { Text("Gateway password") }, singleLine = true, ...
)
```

No `visualTransformation = PasswordVisualTransformation()`. The password is visible on screen. On a shared device or during a screen recording/share, this leaks credentials.

**Fix:**
```kotlin
import androidx.compose.ui.text.input.PasswordVisualTransformation
OutlinedTextField(
    value = password,
    visualTransformation = PasswordVisualTransformation(),
    ...
)
```

Same for `memsToken`.

### 5.2 Agent response text included verbatim in notifications

**File:** `Notifier.kt`, `agentFinished()`

```kotlin
Channels.post(context, Channels.AGENT, id, "Agent finished", text.take(300))
```

**File:** `Channels.kt`, `post()`

```kotlin
.setContentText(text.take(200))
.setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1200)))
```

Notification content is visible on the lock screen by default (Android lock screen notification visibility). If the agent's response contains sensitive information (passwords, PII, private data), it will be visible to anyone who picks up the phone. The cron notification also includes `job.lastError` which may contain stack traces or sensitive path information.

**Fix:** Either set notification visibility to `VISIBILITY_SECRET` for the agent channel, or truncate/redact the content in notifications:

```kotlin
NotificationChannel(AGENT, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
}
```

### 5.3 `SettingsScreen` "Test & save" creates a throwaway `HermesClient` that is never shut down

**File:** `SettingsScreen.kt`

```kotlin
val client = com.eldermoss.hermes.core.net.HermesClient(
    config = { com.eldermoss.hermes.core.net.ServerConfig(serverUrl.trimEnd('/'), username, password) },
)
```

This creates a new `OkHttpClient` (inside `HermesClient`) with its own thread pool and connection pool. It is never closed. On repeated "Test & save" taps, threads and connections accumulate. OkHttp's connection pool will eventually time out idle connections, but the thread pool (`Dispatcher`) will not be reclaimed until GC finalizes the `OkHttpClient`.

**Fix:** Call `client.http.dispatcher.executorService.shutdown()` and `client.http.connectionPool.evictAll()` after the test, or reuse the global `HermesClient` with temporary credentials.

### 5.4 `usesCleartextTraffic="false"` but `DEFAULT_MEMS` and `DEFAULT_SERVER` use `https` — good

The manifest correctly sets `usesCleartextTraffic="false"` and both default URLs use `https`. The Tailscale-only topology means TLS is terminated at the Tailscale layer. No cleartext risk for the default configuration. ✓

### 5.5 Cookie stored as a plain `@Volatile String` in memory — acceptable but note

**File:** `HermesClient.kt`

The session cookie is held in a `@Volatile var cookie: String?` in memory. It is not persisted to disk (correct — no need to survive process death since re-login is automatic). However, it is accessible to any code that holds a `HermesClient` reference. Since `HermesClient` is package-internal to `core/net` and only accessed via `ConnectionManager`, this is acceptable. ✓

---

## Priority 6 — Lifecycle Problems

### 6.1 `LinkService` on API 34 requires `foregroundServiceType` in `startForeground()` call

**File:** `LinkService.kt`, `onCreate()`

```kotlin
startForeground(NOTIF_ID, Channels.link(this))
```

Android 14 (API 34) requires `startForeground(id, notification, foregroundServiceType)` where the type matches the manifest declaration (`dataSync`). Without the type parameter, on API 34 the system throws `MissingForegroundServiceTypeException` (or silently fails on some OEMs). The manifest correctly declares `android:foregroundServiceType="dataSync"`, but the `startForeground` call doesn't pass the type.

**Fix:**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(NOTIF_ID, Channels.link(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
} else {
    startForeground(NOTIF_ID, Channels.link(this))
}
```

`ServiceInfo` requires API 29+; for API 26–28 the two-arg form is fine.

### 6.2 `LinkService.onStartCommand` returns `START_STICKY` — correct but has a restart gap

**File:** `LinkService.kt`

`START_STICKY` means the system will restart the service after it's killed, but with a `null` intent. `onStartCommand` ignores the intent, so this is fine. However, after a system kill and restart, `onCreate` will call `connections.socket.start()` — but `