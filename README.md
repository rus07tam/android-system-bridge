# Android System Bridge

An Android app that runs a small HTTP/WebSocket server on your phone and exposes system-level actions through a simple JSON API. Think of it as a local bridge: scripts, desktop tools, or other apps on the same network (or on the device itself) can read contacts, send SMS, inspect notifications, dump the accessibility tree, and more — without baking all of that into every client.

The server lives inside a foreground service, so Android keeps it alive while you work. A Compose UI lets you start/stop the bridge, pick a port, rotate an auth token, and watch request logs in real time.

## What it does

- **REST API** — `POST /tools/<category>.<action>` with a JSON body
- **WebSocket** — same tool calls, plus push events (clipboard, calls, etc.)
- **Broadcast intents** — trigger tools from other apps without HTTP
- **ContentProvider** — read API logs / clipboard / notification history, or call tools via `ContentResolver.call()`

All tool execution goes through a single registry (`ToolRegistry`), so behavior is consistent no matter which channel you use.

**HTTP API spec:** [openapi.yaml](openapi.yaml) (OpenAPI 3.1, matches `GatewayService` and `ToolRegistry`).

## Requirements

- Android 7.0+ (API 24)
- Android Studio with a recent AGP (project targets SDK 36)
- JDK 11

For release builds you’ll need signing env vars (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) or a `my-upload-key.jks` in the project root. Debug builds use the standard debug keystore.

## Getting started

1. Clone the repo and open it in Android Studio.
2. Build and install the debug APK on a device or emulator.
3. Open **Android System Bridge** on the device.
4. Flip **IPC Engine Host** on — the foreground service starts listening (default port `8080`).
5. Grant the permissions the features you care about need (contacts, SMS, notification listener, accessibility, usage stats, etc.). The app links you to the right system screens.
6. Copy the **Authentication Token** from the UI — every remote request needs it.

By default the server binds to `127.0.0.1` only. Enable **Allow WAN** if you intentionally want it reachable from other machines on your LAN (see [Security](#security)).

### Quick health check

```bash
curl -H "Authorization: Bearer YOUR_TOKEN" http://127.0.0.1:8080/
```

You should get JSON with `status`, `port`, `allow_external`, and `sdk`.

### Example: device info

```bash
curl -X POST http://127.0.0.1:8080/tools/deviceinfo.read \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```

### Example: search contacts

```bash
curl -X POST http://127.0.0.1:8080/tools/contacts.search \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query": "Alice"}'
```

You can also pass the token as a query param: `?token=YOUR_TOKEN` (handy for quick tests, less ideal for production scripts).

## WebSocket API

Connect to `ws://127.0.0.1:8080` with the same Bearer token in the handshake (via your client’s auth headers, depending on the library).

Send a JSON message:

```json
{
  "id": "req-1",
  "category": "deviceinfo",
  "action": "read",
  "params": {}
}
```

The server replies with the tool result and echoes your `id`. Event frames look like:

```json
{
  "type": "event",
  "event_type": "clipboard_changed",
  "data": { "text": "..." },
  "timestamp": 1710000000000
}
```

Other event types include `call_state_changed` when telephony state updates.

## Broadcast integration

Send an explicit broadcast to run a tool from another app:

```kotlin
val intent = Intent("org.ruject.gateway.ACTION_COMMAND").apply {
    setPackage("org.ruject.gateway")
    putExtra("category", "clipboard")
    putExtra("action", "read")
    putExtra("params", "{}")
    // optional: putExtra("response_action", "com.myapp.GATEWAY_REPLY")
}
sendBroadcast(intent)
```

If you set `response_action`, the bridge broadcasts back `status` and `response` (JSON string) on that action.

## ContentProvider

Authority: `org.ruject.gateway.provider`

| URI | Purpose |
|-----|---------|
| `content://org.ruject.gateway.provider/logs` | Recent API call log rows |
| `content://org.ruject.gateway.provider/clipboard` | Clipboard history captured by the bridge |
| `content://org.ruject.gateway.provider/notifications` | Stored notification history |

Direct tool invocation:

```kotlin
val uri = Uri.parse("content://org.ruject.gateway.provider")
val bundle = context.contentResolver.call(
    uri,
  /* method = category */ "deviceinfo",
  /* arg = action */ "read",
  /* extras = params */ null
)
val json = bundle?.getString("response")
```

## Tool reference

Endpoint pattern: `POST /tools/<category>.<action>`  
Body: JSON object of parameters (can be `{}`).

| Category | Actions | Notes |
|----------|---------|-------|
| `deviceinfo` | `read` | Model, battery, memory, storage, network |
| `contacts` | `read`, `search`, `create`, `delete` | Needs contact permissions |
| `calendar` | `list`, `create`, `delete` | Calendar read/write permissions |
| `alarms` | `create`, `list` | `create` opens system alarm UI flow |
| `sms` | `send`, `read`, `calls` | SMS + call log permissions |
| `notifications` | `active`, `dismiss`, `reply` | Requires notification listener service |
| `clipboard` | `read`, `write` | Also monitored for WebSocket events |
| `accessibility` | `dump`, `action`, `global_action` | Requires accessibility service enabled |
| `usagestats` | `list`, `foreground` | Requires usage access permission |
| `intents` | `launch`, `deeplink`, `discovery` | Launch apps, open URLs, list packages |
| `filesystem` | `list`, `read`, `write`, `delete` | App-internal storage only |
| `media` | `mute`, `volume`, `media_key` | Audio / media key controls |

Responses are JSON objects with a `status` field (`success` or `error`) and category-specific payloads.

The in-app **Sandbox Execute** tab is useful for trying `category` / `action` / params without leaving the phone.

## Project layout

```
app/src/main/java/org/ruject/gateway/
├── MainActivity.kt              # Compose UI, permissions, server toggle
├── services/
│   ├── GatewayService.kt        # HTTP + WebSocket server
│   ├── GatewayAccessibilityService.kt
│   └── GatewayNotificationListenerService.kt
├── tools/ToolRegistry.kt        # All tool implementations
├── receivers/GatewayBroadcastReceiver.kt
├── providers/GatewayContentProvider.kt
└── data/                        # Room DB for logs & history
```

Application ID: `org.ruject.gateway`

## Building & tests

```bash
./gradlew assembleDebug
./gradlew test
```

Unit tests use Robolectric; instrumented tests use the standard AndroidJUnit runner.

Optional secrets are loaded via the Secrets Gradle Plugin from `.env` (see `.env.example` if present). Most bridge features do not require external API keys.

## CI / GitHub Actions

| Workflow | Trigger | Artifact |
|----------|---------|----------|
| [Nightly](.github/workflows/nightly.yml) | Every `push` | Debug APK on the **`nightly`** release (prerelease) |
| [Release](.github/workflows/release.yml) | Git tag `v*` (e.g. `v1.0.0`) or manual dispatch | Signed release APK |

### Single nightly on Releases

Yes. The nightly workflow **deletes** the previous `nightly` tag/release and publishes a new one with the same name. On the Releases page you always see one **Nightly** entry (updated in place), separate from versioned releases like `v1.0.0`.

### Repository secrets (release builds only)

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Upload keystore file, base64-encoded |
| `ANDROID_STORE_PASSWORD` | Keystore password (`STORE_PASSWORD`) |
| `ANDROID_KEY_PASSWORD` | Key password (`KEY_PASSWORD`) |
| `ANDROID_KEY_ALIAS` | Key alias (default in Gradle: `upload`) |

Create a release locally or from CI:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Nightly builds use an ephemeral debug keystore generated in CI; no signing secrets required.

## Security

This app is powerful by design. It can touch personal data (contacts, SMS, call logs, notifications, clipboard) and drive the UI through accessibility.

- **Keep “Allow WAN” off** unless you trust every device on your network.
- **Treat the auth token like a password** — regenerate it if it leaks.
- **Do not expose the bridge to the public internet** without additional hardening (TLS, IP allowlists, etc.). The embedded server speaks plain HTTP.
- Only install and enable accessibility / notification listener access when you understand what callers can do.

Use it on devices you own, for automation and debugging you control.

## License

No license file is included yet. Add one before distributing the project publicly.
