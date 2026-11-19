# Hermes Mobile

Native Android client for the [Hermes Agent](https://hermes-agent.nousresearch.com/docs). Chat with your agent from anywhere: streaming responses, session resume, command approvals and clarify answers in the palm of your hand.

Kotlin, no Electron. Talks the same JSON-RPC-over-WebSocket wire protocol as the Hermes desktop app, against a remote gateway (HTTPS plus one-use, 30-second WebSocket tickets minted from an authenticated login).

## Features

- **Streaming chat** — live token deltas with Markdown rendering, including code blocks and tables
- **Sessions** — list, resume, and pick up any conversation where it left off
- **Approvals** — native cards for command approvals, with every choice the agent offers
- **Clarify** — native question UI with selectable choices and free text
- **File activity** — see the files the agent touches during a run
- **Gateway connection** — password login, ticket-based WebSocket auth, automatic reconnect

## Requirements

- Android 8.0 (API 26) or newer
- A reachable Hermes Agent gateway

## Build

```bash
./gradlew assembleRelease
```

Toolchain: Gradle 8.9, Android SDK 34, JDK 17. Release builds are signed and published as APKs under [Releases](../../releases).

## Roadmap

Planned, in priority order:

1. Stop generation, message actions, richer session management, attachments
2. Foreground service with push notifications for approvals and task completion
3. Multi-server profiles, biometric app lock, encrypted credential storage
4. Voice input and output, push-to-talk
5. Live tool-call feed, cron manager, memory browser
6. Server dashboard, home screen widget, share-sheet integrations

## License

All rights reserved. Private project.
