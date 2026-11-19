# Hermes Mobile — Design

Reference for every server contract this app depends on, and why each
architecture choice was made. Verified against the Hermes desktop app source
(`apps/desktop/src` in NousResearch/hermes-agent) and against the live gateway
on Asgard (Hermes 0.23.2).

## Server topology on Asgard

- The Hermes **gateway** (`hermes gateway run`) is the API server. It serves
  both the JSON-RPC WebSocket and a FastAPI REST surface on **127.0.0.1:9120**.
- Tailscale serve exposes it tailnet-only:
  `https://asgard.tailbd5ca8.ts.net:9120 -> http://127.0.0.1:9120`.
  Nothing is reachable from the public internet.
- `hermes dashboard --port 9119` (the web dashboard) is a separate process;
  the app does not talk to it.
- The old `hermes-serve` on 9119 is gone; the gateway on 9120 is what the
  desktop app uses and what this app uses. No server reconfiguration was
  needed for chat/sessions/skills/schedules.

## Authentication

The gateway has no static API key. Its auth model (verified live:
`auth_required: true, auth_providers: ["basic"], auth_flows: ["cookie","native_pkce"]`):

1. `GET /api/status` — public. Reports `auth_providers`.
2. `POST /auth/password-login` `{"provider":"basic","username":..., "password":...}`
   → `200` + `Set-Cookie` session cookie(s). 401 bad credentials, 404 no
   password provider, 429 rate-limited.
3. Every `/api/*` request sends that cookie.
4. WebSockets cannot send cookies on some clients, so the gateway mints
   one-use 30-second tickets: `POST /api/auth/ws-ticket` (with cookie) →
   `{"ticket":..., "ttl_seconds":30}`, then dial `GET /api/ws?ticket=<t>`.

The credential pair is the app's "API key". It is stored in
EncryptedSharedPreferences (Android Keystore-backed AES256 keys), never
logged, never committed. Settings → Connection edits it.

## Wire protocol (chat)

JSON-RPC 2.0 over the WebSocket:

- client → server: `{"jsonrpc":"2.0","id":N,"method":M,"params":P}`
- replies: `{"jsonrpc":"2.0","id":N,"result":R}` / `{"id":N,"error":{code,message}}`
- server events: `{"jsonrpc":"2.0","method":"event","params":{type,session_id,payload,seq}}`

Methods used: `session.create {}`, `session.resume {session_id}`,
`session.list {}`, `prompt.submit {session_id, text}` (ack may take minutes;
turn progress arrives as events), `session.interrupt`,
`approval.respond {session_id, request_id, choice}`,
`clarify.respond {session_id, request_id, answer}`.

Events used: `thinking.delta {text}`, `message.delta {text}`,
`message.complete {text}`, `tool.start {name, tool_id}`,
`tool.complete {name, tool_id, result}`, `session.title {session_id, title}`,
`approval.request`, `clarify.request`, `sessions.changed`.

## REST endpoints (read/manage)

All cookie-authenticated under `/api`:

Sessions (state.db):
- `GET /api/sessions?limit&offset&min_messages&archived&order` → `{sessions:[SessionInfo]}`
- `GET /api/sessions/search?q=` → search hits
- `GET /api/sessions/{id}/messages?limit&offset&order=latest&include_compacted=true`
  → `{session_id, messages, pagination?}` (order-echo guard: only trust a page
  that echoes `order:latest` as the tail)
- `PATCH /api/sessions/{id}` `{title|pinned|archived|unread}` — rename
- `DELETE /api/sessions/{id}`

Skills:
- `GET /api/skills` → `SkillInfo[]`
- `GET /api/skills/content?name=` → `{content, name, path}` (raw SKILL.md)
- `PUT /api/skills/toggle` `{name, enabled}`

Schedules (cron, ~/.hermes/cron/jobs.json):
- `GET /api/cron/jobs` → `CronJob[]`
- `GET /api/cron/jobs/{id}/runs?limit=` → `{runs:[SessionInfo]}` (each run is a session)
- `POST /api/cron/jobs` `{name?, prompt, schedule, deliver?}` 
- `PUT /api/cron/jobs/{id}` `{updates:{enabled, prompt, schedule, ...}}`
- `POST /api/cron/jobs/{id}/pause` | `/resume` | `/trigger`
- `DELETE /api/cron/jobs/{id}`

## Memory (MemS) — sidecar service

The Hermes API has **no MemS record endpoints** (the `/api/learning/*` graph
is a different system; `memory_providers.py` only configures providers). MemS
lives in `~/.hermes/mems/mems.sqlite3` and is normally accessed only by the
agent's own `mems` tool.

Rather than raw SQL from the phone (which would bypass supersession,
retraction and embedding invariants), Asgard runs a small stdlib-only sidecar:

- `server/mems_api.py` (in this repo, deployed to `~/.hermes/mems-api/`)
- imports the **actual MemS plugin** (`~/.hermes/plugins/mems`) and calls its
  `MemSProvider.handle_tool_call("mems", args)` — the exact same code path the
  agent uses, so `add`/`correct`/`retract` semantics and **embedding
  regeneration via Ollama (all-minilm at 127.0.0.1:11434)** are guaranteed
  identical. Edits that change text always get a fresh vector.
- Read-only single-record fetch is a plain `SELECT` (no write-path risk).
- Bound to 127.0.0.1:<port>, proxied tailnet-only via tailscale serve,
  bearer-token auth (token in `~/.hermes/mems-api/token`, 0600).
- Runs as the systemd user service `mems-api.service`.

Sidecar endpoints:
- `GET  /api/mems/search?q=&as_of=` — hybrid ranked recall (plugin `_rank`)
- `GET  /api/mems/list` — 50 most recent non-retracted
- `GET  /api/mems/{id}` — one record (read-only SQL)
- `POST /api/mems/add` `{text, category?, importance?, occurred_at?, valid_from?, valid_to?, confidence?, source?}`
- `POST /api/mems/correct` `{id, text, category?, ...}` — supersedes old, embeds new
- `POST /api/mems/retract` `{id}`

No Hermes core code and no MemS plugin code is modified.

## Notifications

Sideloaded APK + tailnet-only connectivity rules out FCM. Chosen:

1. **Foreground service** (toggle in Settings): keeps the gateway WebSocket
   alive in the background, shows a persistent low-priority notification, and
   posts notifications on cron state changes (poll `/api/cron/jobs` every 60s,
   diff last_run_at/last_error/latest_execution) and on `message.complete`
   events for sessions the user is not actively watching (agent finished a
   long task). Instant, works anywhere on the tailnet, no third party.
2. **WorkManager fallback** (when the service is off): periodic 15-min cron
   poll, the minimum WorkManager allows. Delayed but battery-cheap.

Each notification type (cron completions, cron failures, agent task
completions) is individually toggleable in Settings.

## Stack

Kotlin + Jetpack Compose, single `:app` module with strict package boundaries:

- `core/net` — HermesClient (HTTP + WS + auth). Only place that knows the wire.
- `core/data` — repositories per feature, plain suspend functions over HermesClient.
- `core/store` — settings + Keystore-encrypted credentials.
- `feature/<name>` — one package per bottom-nav tab (UI + ViewModel).
- `ui` — theme (Material 3 dynamic light/dark), shared components.

Dependencies and why each earns its size: Compose/Material3 (the entire UI),
OKHttp (HTTP + WebSocket in one small lib), kotlinx-serialization (lenient
JSON parsing), datastore-preferences (settings), security-crypto (Keystore
storage), WorkManager (poll fallback), lifecycle-viewmodel (state). No DI
framework, no image loader, no Room — server holds the state.

Min SDK 26, target/compile SDK 34, Kotlin 1.9.24, AGP 8.5.2, Gradle 8.9 —
the exact toolchain proven on the build host.

## Explicitly out of scope for v1

Voice, Profiles, Models, Providers, Tools, Gateway, Persona, Kanban, Office,
Artifacts. Extension points: one package per feature and the repository
interfaces in `core/data` mean a new tab is a new feature package + one nav
entry; new server calls go in `core/net` only.

## Testing

- Unit tests (JUnit + MockWebServer): auth flow (login → cookie → ticket),
  endpoint request shapes and response parsing, cron diff logic, reconnect
  backoff state machine, MemS sidecar mapping.
- Live smoke: the same REST/WS contracts were exercised against the running
  gateway from Asgard during development.
- No emulator/device on the build host at release time (KVM exists; a headless
  emulator run is future work) — see the README for what that leaves untested.
