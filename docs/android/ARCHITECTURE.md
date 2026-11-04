# Android Architecture Guide

This document details the architectural layers, component responsibilities, and lifecycle management for the Android implementation of TG WS Proxy.

---

## 1. Overall System Architecture

The application is organized into 4 primary layers:

```
┌────────────────────────────────────────────────────────┐
│                      UI Layer                          │
│   (Jetpack Compose: MainScreen, Settings, ViewModel)   │
└───────────────────────────┬────────────────────────────┘
                            │ Intents / StateFlow
┌───────────────────────────▼────────────────────────────┐
│                    Service Layer                       │
│    (ProxyForegroundService, Notification, WakeLock)     │
└───────────────────────────┬────────────────────────────┘
                            │ Coroutine Scope
┌───────────────────────────▼────────────────────────────┐
│                    Networking Core                     │
│  - MtprotoServer (ServerSocket on 127.0.0.1)           │
│  - ConnectionBridge (Bi-directional bridge worker)     │
│  - MtprotoCrypto (AES-CTR, Handshake, Re-encryption)   │
│  - MsgSplitter (Abridged/Intermediate packet framing)  │
│  - WsClient (OkHttp WebSocket to kws*.web.telegram.org)│
└───────────────────────────┬────────────────────────────┘
                            │ DataStore
┌───────────────────────────▼────────────────────────────┐
│                      Data Layer                        │
│  (PreferencesManager: port, secret, CF worker domains) │
└────────────────────────────────────────────────────────┘
```

---

## 2. Networking Core Layer

### 2.1. `MtprotoServer.kt`
* Binds a `java.net.ServerSocket` exclusively to the loopback interface:
  ```kotlin
  val serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
  ```
* Runs an `accept()` loop inside a coroutine on `Dispatchers.IO`.
* Spawns a dedicated child coroutine for each inbound connection, delegating handling to `ConnectionBridge`.
* Tracks open connection counts and reports live metrics to `StatsManager`.

### 2.2. `ConnectionBridge.kt`
Manages the lifecycle of a single proxy session:
1. Reads the 64-byte handshake via `InputStream.readNBytes(64)`.
2. Validates it using `MtprotoCrypto.tryHandshake(handshake, secret)`.
3. If validation fails, closes the socket immediately.
4. If valid, generates `relay_init = MtprotoCrypto.generateRelayInit(protoTag, dcIdx)`.
5. Initializes the four streaming ciphers in `CryptoCtx`.
6. Establishes a `WsClient` connection to `wss://kws{dc}.web.telegram.org/apiws`.
7. Sends `relay_init` as the first binary WebSocket frame.
8. Launches two parallel streaming workers:
   * **TCP -> WS:** Reads TCP socket -> decrypts via `clt_dec` -> frames via `MsgSplitter` -> encrypts via `tg_enc` -> sends via WS.
   * **WS -> TCP:** Receives from WS -> decrypts via `tg_dec` -> encrypts via `clt_enc` -> writes to TCP socket `OutputStream`.

### 2.3. `WsClient.kt`
* Wrapper around `okhttp3.WebSocket` and `okhttp3.WebSocketListener`.
* Inbound binary WebSocket messages from Telegram are funneled into a thread-safe `kotlinx.coroutines.channels.Channel<ByteString>(Channel.BUFFERED)`.
* Triggers clean teardown of the corresponding local TCP client socket if the WebSocket drops, signaling Telegram to reconnect.

### 2.4. `MsgSplitter.kt`
* Ensures that fragmented or concatenated MTProto messages in the TCP stream are cleanly broken into individual WebSocket binary frames (see formulas in [`PROTOCOL_SPEC.md`](PROTOCOL_SPEC.md)).
* Maintains an internal `ByteArray` buffer and exposes `split(chunk, plain)` and `flush()` methods.

---

## 3. Background Service Layer

### 3.1. `ProxyForegroundService.kt`
* Inherits from `android.app.Service`.
* Immediately elevates to foreground state upon launch:
  ```kotlin
  startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  ```
* Runs `MtprotoServer` within its own `CoroutineScope(Dispatchers.IO + SupervisorJob())`.
* Manages `PowerManager.WakeLock`:
  * Holds a `PARTIAL_WAKE_LOCK` when active connections exist to prevent CPU sleep during background file downloads or calls when the screen is locked.

### 3.2. `ConnectivityObserver.kt`
* Observes network transitions via `ConnectivityManager.registerDefaultNetworkCallback`.
* When switching between Wi-Fi and Cellular (or when IP routes change), cleanly tears down stale WebSocket connections, prompting Telegram to re-handshake seamlessly over the new network.

---

## 4. User Interface Layer (UI)

### 4.1. Main Screen (`MainScreen.kt`)
* **Service Toggle (Switch):** Starts or stops the proxy service.
* **Status Pill:**
  * Green: "Active (127.0.0.1:1443)"
  * Neutral/Grey: "Stopped"
  * Red: "Error (Port in use / Network failure)"
* **"Connect in Telegram" Action Button:**
  Dispatches an implicit Intent:
  ```kotlin
  val uri = Uri.parse("tg://proxy?server=127.0.0.1&port=$port&secret=$secretHex")
  val intent = Intent(Intent.ACTION_VIEW, uri)
  context.startActivity(intent)
  ```
  Telegram catches this intent and instantly prompts the user with an "Enable Proxy" modal dialog.
* **"Copy Proxy Link" Action:** Copies `tg://proxy?...` or `https://t.me/proxy?...` to clipboard.
* **Live Traffic Monitor:** Displays inbound/outbound transfer rates (KB/MB) and active socket count.

### 4.2. Settings Screen (`SettingsScreen.kt`)
* Local port configuration (default `1443`).
* MTProto secret input and random hex generator.
* Optional Cloudflare Worker / CDN domain override (for networks where `web.telegram.org` is completely blocked).
* "Start on Boot" toggle (`BOOT_COMPLETED` permission).
