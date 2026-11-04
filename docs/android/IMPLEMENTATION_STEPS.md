# Implementation Steps (Roadmap)

This document is the actionable development roadmap for an AI agent or software engineer. Follow the phases in chronological order and verify each step before proceeding.

---

## Phase 1. Android Project Initialization
- [x] Create directory `android/` in the repository root.
- [x] Initialize Gradle project (Kotlin DSL):
  - [x] `android/settings.gradle.kts` (include `:app` module)
  - [x] `android/build.gradle.kts` (root build script, Android Application, Kotlin Android, Compose Compiler plugins)
  - [x] `android/gradle/wrapper/` (Gradle Wrapper 8.7+)
  - [x] `android/app/build.gradle.kts` with required dependencies:
    - `com.squareup.okhttp3:okhttp:4.12.0`
    - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
    - `androidx.core:core-ktx:1.13.1`
    - `androidx.lifecycle:lifecycle-service:2.8.0`
    - `androidx.datastore:datastore-preferences:1.1.1`
    - Jetpack Compose BOM and Material 3 packages.
- [x] Configure `android/app/src/main/AndroidManifest.xml` with required permissions (`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`).

---

## Phase 2. Core Cryptography & Protocol Engine
*Refer to specifications in [`PROTOCOL_SPEC.md`](PROTOCOL_SPEC.md).*

- [x] Create `MtprotoCrypto.kt`:
  - [x] Implement AES-CTR-128 / AES-CTR-256 using standard `javax.crypto.Cipher`.
  - [x] Implement SHA-256 hashing via `MessageDigest`.
  - [x] Function `tryHandshake(handshake: ByteArray, secret: ByteArray)`: decrypt, validate protocol tag, extract `dcId` and `isMedia`.
  - [x] Function `generateRelayInit(protoTag: ByteArray, dcIdx: Short)`: generate 64 random bytes, validate reserved sequences, encrypt keystream tail.
  - [x] Class `CryptoCtx` managing four ciphers (`cltDec`, `cltEnc`, `tgEnc`, `tgDec`) with 64-byte initial counter fast-forwarding (`cipher.update(zeros64)`).
- [x] Create `MsgSplitter.kt`:
  - [x] Support Abridged protocol (parse 1-byte or 4-byte length prefix).
  - [x] Support Intermediate protocol (parse 4-byte uint32 length prefix).
  - [x] Buffer partial chunks across network reads and implement `flush()`.
- [x] **Write Unit Tests (`test/`):**
  - [x] Packet framing unit test `MsgSplitterTest`.
  - [x] Cryptographic compatibility test `HandshakeCryptoTest` comparing test vectors against outputs from Python.

---

## Phase 3. WebSocket Transport & Connection Bridge
- [x] Create `WsClient.kt`:
  - [x] Establish connection via `OkHttpClient.newWebSocket`.
  - [x] Set header: `Sec-WebSocket-Protocol: binary`.
  - [x] Send initial binary frame containing `relay_init` upon connection open.
  - [x] Forward inbound binary frames to a Kotlin `Channel<ByteArray>`.
- [x] Create `ConnectionBridge.kt`:
  - [x] Accept client TCP connection from Telegram.
  - [x] Invoke `tryHandshake`.
  - [x] Select target domain (`kws{dc}.web.telegram.org` or `kws{dc}-1.web.telegram.org` for media).
  - [x] Run bidirectional streaming loops:
    - [x] `tcpToWs()`: read TCP socket -> decrypt `cltDec` -> frame `MsgSplitter` -> encrypt `tgEnc` -> send WebSocket binary frame.
    - [x] `wsToTcp()`: read WebSocket message -> decrypt `tgDec` -> encrypt `cltEnc` -> write to TCP socket.
  - [x] Ensure clean socket disposal on disconnections.
- [x] Add Cloudflare Anycast Balancer (`DomainBalancer.kt`) and Worker failover support (`FallbackManager.kt`).

---

## Phase 4. Local Server & Foreground Service
- [x] Create `MtprotoServer.kt`:
  - [x] Bind `ServerSocket("127.0.0.1", port)`.
  - [x] Run `accept()` loop on `Dispatchers.IO`.
  - [x] Collect live statistics (transferred bytes, active session count).
- [x] Create `ProxyForegroundService.kt`:
  - [x] Elevate to `startForeground` with ongoing notification.
  - [x] Setup high-importance `NotificationChannel`.
  - [x] Add "Stop" action directly in the notification.
  - [x] Manage `WakeLock` to prevent CPU sleep during active streaming.
- [x] Create `ConnectivityObserver.kt` to teardown and recycle stale connections across Wi-Fi ↔ LTE handoffs.

---

## Phase 5. User Interface (Jetpack Compose)
- [x] Create `MainViewModel.kt`:
  - [x] Track service state (Running / Stopped / Error).
  - [x] Load and save settings via `PreferencesManager` (DataStore).
  - [x] Expose live metrics as `StateFlow<ProxyStats>`.
- [x] Create `MainScreen.kt`:
  - [x] Toggle switch for Starting / Stopping the proxy service.
  - [x] **"Connect in Telegram"** button (launches `Intent.ACTION_VIEW` with `tg://proxy?server=127.0.0.1&port=1443&secret=...`).
  - [x] **"Copy Link"** button.
  - [x] Live stats card (Inbound/Outbound KB/MB, active sockets).
- [x] Create Settings Screen:
  - [x] Port input field (default 1443).
  - [x] Secret input field with a random hex generator button.
  - [x] Custom Cloudflare Worker domain input.
  - [x] About section with app version, GitHub link, and author credits.

---

## Phase 6. Testing & End-to-End Verification
- [x] Build debug APK (`./gradlew assembleDebug`).
- [x] Test on physical device (Pixel 7):
  1. [x] Launch app and tap "Start".
  2. [x] Tap "Connect in Telegram".
  3. [x] Verify Telegram shows "Connected".
  4. [x] Test message sending, channel media loading, and video playback.
  5. [x] Verify proxy stays active when the device screen is turned off and during Wi-Fi ↔ LTE transitions.
- [x] Build signed Release APK (`./gradlew assembleRelease`).
- [x] Configure automated GitHub Actions release workflow (`.github/workflows/android-release.yml`).
