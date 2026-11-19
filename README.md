# Hermes Mobile

Hermes Mobile is a native Android app for talking to your own AI agent from anywhere. Where the Hermes desktop app lives on your computer, Hermes Mobile lives in your pocket: a fast, focused chat client built to be the remote control for the agent that runs your life.

## What It Does

- **Chat anywhere** — full conversations with your Hermes agent, with live streaming responses rendered in clean Markdown. Tool calls show as compact one-line cards; tap to expand their output. Approvals and clarifying questions arrive as tappable cards mid-stream.
- **Pick up where you left off** — every session from your desktop is there. Search, open, rename, delete.
- **Skills** — browse installed skills, read their SKILL.md, enable or disable them.
- **Schedules** — full cron manager: status, last run, recent run outputs, create, edit, pause, resume, delete.
- **Memory (MemS)** — optional: search, view, add, correct (supersede) and retract memories via a small sidecar on the Hermes host. Corrections go through the same code path the agent uses, so history, supersession and re-embedding stay consistent. The tab only appears when a sidecar is configured.
- **Stay in command** — when the agent needs permission to run something, it asks you directly with native approval cards. When it needs information, it asks a clarifying question you can answer in a tap.
- **Notifications** — cron completions and failures, and long agent tasks that finish while the phone is backgrounded. Each type is individually switchable.
- Light and dark theme; everything renders over Tailscale only — nothing is exposed to the public internet.

An agent is only useful if you can reach it. Hermes Mobile closes the gap between "at my desk" and "wherever I am," so a long-running task can keep going while you are away — and you can answer your agent's questions the moment it asks.

Voice input/output, Profiles, Models, Providers, Tools, Gateway, Persona, Kanban, Office and Artifacts are deliberately out of scope for v1; the architecture leaves clean seams for them (see "Adding a feature").

## Requirements

- A Hermes gateway (Hermes Agent 0.23.x) reachable over Tailscale. The gateway is the same API server the Hermes desktop app uses. Nothing here is specific to one machine — point it at any gateway.
- Android 8.0+ (API 26). The APK is sideloaded; no Play Services needed, which is why notifications do not use FCM.

## Install

1. Grab the latest APK from [Releases](https://github.com/elder-moss/hermes-mobile/releases) (sideload it: allow "install unknown apps" for your browser or file manager).
2. First launch drops you into Settings. Fill in:
   - **Gateway URL** — `https://<your-host>:9120` (your gateway's Tailscale name or 100.x address + its port).
   - **Gateway username / password** — the dashboard basic-auth credentials (`dashboard.basic_auth` or `HERMES_DASHBOARD_BASIC_AUTH_*` on the server). This credential pair is the app's API key; the server has no separate static key.
   - Tap **Test & save** — it verifies the gateway and the login, then stores both.
3. Optional: the **Memory (MemS)** tab needs a small sidecar on the Hermes host (below). Without it the app works fine — the Memory tab simply doesn't appear.
4. Done. The bottom bar appears once credentials are saved.

Credentials are stored in EncryptedSharedPreferences backed by the Android Keystore. They are never logged or committed.

## Architecture

Kotlin + Jetpack Compose, single `:app` module, strict package boundaries:

```
core/net    HermesClient (HTTP + auth + one-shot 401 retry),
            GatewaySocket (JSON-RPC over WebSocket, reconnect + backoff),
            models      — only place that knows the wire protocol
core/data   SessionsRepository, SkillsRepository, CronRepository, MemRepository
core/store  SettingsRepository (DataStore settings + Keystore-encrypted credentials)
feature/    chat | sessions | skills | schedules | memory | settings
ui          theme (Material 3, light/dark), shared components, small markdown renderer
notify      LinkService (foreground WS), CronPollWorker (WorkManager fallback), CronDiff
```

Wire contract and every endpoint used are documented in [docs/DESIGN.md](docs/DESIGN.md), verified against the desktop app source and the live gateway. Server changes are absorbed in `core/net` alone.

Dependencies and why each earns its size: Compose/Material3 (the entire UI), OkHttp (HTTP + WebSocket in one small library), kotlinx-serialization (lenient JSON against a moving backend), datastore-preferences (settings), security-crypto (Keystore storage), WorkManager (poll fallback). No DI framework, no Room, no image loader — the server holds the state.

### Server side

- The gateway (`hermes gateway run`) serves both the JSON-RPC WebSocket and the REST API. Expose it tailnet-only via `tailscale serve`, e.g. `https://<your-host>:9120 -> http://127.0.0.1:9120`. That is the whole server-side requirement for chat/sessions/skills/schedules.
- **Memory (MemS) sidecar (optional)**: the Hermes API has no memory-record endpoints, so `server/mems_api.py` (stdlib-only) imports the MemS plugin and exposes its own search/list/add/correct/retract over HTTP — the same code path the agent uses, guaranteeing supersession, retraction and Ollama re-embedding (all-minilm at 127.0.0.1:11434) stay consistent. Deploy it anywhere on your Hermes host: bind to 127.0.0.1, proxy tailnet-only via `tailscale serve`, bearer token in a 0600 file. Example deployment (systemd user unit, port 9471) is what runs on the author's server; the app hides the Memory tab until this is configured. No Hermes core or MemS plugin code is modified.

### Notifications

FCM is useless here (sideloaded APK, tailnet-only connectivity), so:

- **Link service** (Settings toggle): a foreground service keeps the gateway WebSocket alive while the app is backgrounded — instant notifications for cron state changes (60s poll + diff) and agent turns that complete in sessions you're not watching. Cost: a persistent low-priority notification and a socket; it only works on the tailnet.
- **Fallback**: with the service off, a WorkManager job polls cron state every 15 minutes (WorkManager's floor). Battery-cheap, delayed.

## Build

Built entirely on the host (Fedora, no Android Studio):

```bash
sudo dnf install java-21-openjdk-devel   # or any JDK 17+
# Android cmdline-tools: install platform-tools, platforms;android-34, build-tools;34.0.0
export ANDROID_HOME=~/android-sdk
./gradlew assembleDebug        # debug build
./gradlew test                 # unit tests (client, models, backoff, cron diff, mems repo)
./gradlew assembleRelease      # signed release (needs the keystore, below)
```

Gradle wrapper included; a single `./gradlew` command is enough. If the host default JDK is newer than the toolchain likes, set `org.gradle.java.home` in `~/.gradle/gradle.properties`.

## Releases

Releases carry a signed APK, a version number, a changelog and a SHA-256 checksum. Signing material lives **outside the repo** at `~/.config/android-keystore/` (`hermes-mobile.jks` + `keystore.properties`, mode 0600); `app/build.gradle.kts` picks it up automatically when present and builds unsigned otherwise. To regenerate from scratch:

```bash
keytool -genkeypair -v -keystore ~/.config/android-keystore/hermes-mobile.jks \
  -alias hermes-mobile -keyalg RSA -keysize 4096 -validity 10950
# then write ~/.config/android-keystore/keystore.properties with storeFile,
# storePassword, keyAlias, keyPassword (chmod 600)
```

A new keystore means a new app identity for update purposes: uninstall the old app first, and notifications/settings reset with it.

## Adding a feature

1. Server calls go in `core/net` (client/socket) and a repository in `core/data` — UI never touches the wire.
2. New tab = new package under `feature/` with a ViewModel + Screen, plus one entry in `ui/AppRoot.kt` (`tabs` list + one `composable(...)` route).
3. Out-of-scope v1 areas (voice, models, profiles...) slot in the same way; the gateway already exposes most of their endpoints (see `apps/desktop/src/api` in the hermes-agent repo for the full surface).

## Testing

- 21 unit tests: auth flow and one-shot 401 retry (MockWebServer), wire-shape parsing against payloads captured live from the gateway, reconnect backoff policy, cron notification diffing, MemS repository (bearer auth, error mapping).
- The REST and WS contracts were additionally exercised live against the running gateway during development (login, ticket, session create/interrupt, sessions/messages/skills/cron/search, sidecar search/list).
- No device or emulator was attached at release time, so on-device behaviour (streaming UX, notifications, Keystore persistence) is verified by protocol-level tests but not hands-on yet. The host has KVM; a headless emulator run is the next verification step.

## Status

v1 scope complete. Roadmap candidates: message actions + stop-refinement, attachments, richer tool-call feed, multi-server profiles, biometric lock, voice.
