# Implementation Steps (Roadmap)

This document is the actionable development roadmap for an AI agent or software engineer. Follow the phases in chronological order and verify each step before proceeding.

---

## Phase 1. Android Project Initialization
- [ ] Create directory `android/` in the repository root.
- [ ] Initialize Gradle project (Kotlin DSL):
  - [ ] `android/settings.gradle.kts` (include `:app` module)
  - [ ] `android/build.gradle.kts` (root build script, Android Application, Kotlin Android, Compose Compiler plugins)
  - [ ] `android/gradle/wrapper/` (Gradle Wrapper 8.7+)
  - [ ] `android/app/build.gradle.kts` with required dependencies:
    - `com.squareup.okhttp3:okhttp:4.12.0`
    - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
    - `androidx.core:core-ktx:1.13.1`
    - `androidx.lifecycle:lifecycle-service:2.8.0`
    - `androidx.datastore:datastore-preferences:1.1.1`
    - Jetpack Compose BOM and Material 3 packages.
- [ ] Configure `android/app/src/main/AndroidManifest.xml` with required permissions (`INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`).

---

## Phase 2. Core Cryptography & Protocol Engine
*Refer to specifications in [`PROTOCOL_SPEC.md`](PROTOCOL_SPEC.md).*

- [ ] Create `MtprotoCrypto.kt`:
  - [ ] Implement AES-CTR-128 / AES-CTR-256 using standard `javax.crypto.Cipher`.
  - [ ] Implement SHA-256 hashing via `MessageDigest`.
  - [ ] Function `tryHandshake(handshake: ByteArray, secret: ByteArray)`: decrypt, validate protocol tag, extract `dcId` and `isMedia`.
  - [ ] Function `generateRelayInit(protoTag: ByteArray, dcIdx: Short)`: generate 64 random bytes, validate reserved sequences, encrypt keystream tail.
  - [ ] Class `CryptoCtx` managing four ciphers (`cltDec`, `cltEnc`, `tgEnc`, `tgDec`) with 64-byte initial counter fast-forwarding (`cipher.update(zeros64)`).
- [ ] Create `MsgSplitter.kt`:
  - [ ] Support Abridged protocol (parse 1-byte or 4-byte length prefix).
  - [ ] Support Intermediate protocol (parse 4-byte uint32 length prefix).
  - [ ] Buffer partial chunks across network reads and implement `flush()`.
- [ ] **Write Unit Tests (`test/`):**
  - [ ] Packet framing unit test `MsgSplitterTest`.
  - [ ] Cryptographic compatibility test `HandshakeCryptoTest` comparing test vectors against outputs from Python.

---

## Phase 3. WebSocket Transport & Connection Bridge
- [ ] Create `WsClient.kt`:
  - [ ] Establish connection via `OkHttpClient.newWebSocket`.
  - [ ] Set header: `Sec-WebSocket-Protocol: binary`.
  - [ ] Send initial binary frame containing `relay_init` upon connection open.
  - [ ] Forward inbound binary frames to a Kotlin `Channel<ByteArray>`.
- [ ] Create `ConnectionBridge.kt`:
  - [ ] Accept client TCP connection from Telegram.
  - [ ] Invoke `tryHandshake`.
  - [ ] Select target domain (`kws{dc}.web.telegram.org` or `kws{dc}-1.web.telegram.org` for media).
  - [ ] Run bidirectional streaming loops:
    - [ ] `tcpToWs()`: read TCP socket -> decrypt `cltDec` -> frame `MsgSplitter` -> encrypt `tgEnc` -> send WebSocket binary frame.
    - [ ] `wsToTcp()`: read WebSocket message -> decrypt `tgDec` -> encrypt `cltEnc` -> write to TCP socket.
  - [ ] Ensure clean socket disposal on disconnections.
- [ ] Add optional Cloudflare Worker failover support (`FallbackManager.kt`).

---

## Phase 4. Local Server & Foreground Service
- [ ] Create `MtprotoServer.kt`:
  - [ ] Bind `ServerSocket("127.0.0.1", port)`.
  - [ ] Run `accept()` loop on `Dispatchers.IO`.
  - [ ] Collect live statistics (transferred bytes, active session count).
- [ ] Create `ProxyForegroundService.kt`:
  - [ ] Elevate to `startForeground` with ongoing notification.
  - [ ] Setup high-importance `NotificationChannel`.
  - [ ] Add "Stop" action directly in the notification.
  - [ ] Manage `WakeLock` to prevent CPU sleep during active streaming.
- [ ] Create `ConnectivityObserver.kt` to teardown and recycle stale connections across Wi-Fi ↔ LTE handoffs.

---

## Phase 5. User Interface (Jetpack Compose)
- [ ] Create `MainViewModel.kt`:
  - [ ] Track service state (Running / Stopped / Error).
  - [ ] Load and save settings via `PreferencesManager` (DataStore).
  - [ ] Expose live metrics as `StateFlow<ProxyStats>`.
- [ ] Create `MainScreen.kt`:
  - [ ] Toggle switch for Starting / Stopping the proxy service.
  - [ ] **"Connect in Telegram"** button (launches `Intent.ACTION_VIEW` with `tg://proxy?server=127.0.0.1&port=1443&secret=...`).
  - [ ] **"Copy Link"** button.
  - [ ] Live stats card (Inbound/Outbound KB/MB, active sockets).
- [ ] Create Settings Screen:
  - [ ] Port input field (default 1443).
  - [ ] Secret input field with a random hex generator button.
  - [ ] Custom Cloudflare Worker domain input.

---

## Phase 6. Testing & End-to-End Verification
- [ ] Build debug APK (`./gradlew assembleDebug`).
- [ ] Test on Android emulator or physical device:
  1. Launch app and tap "Start".
  2. Tap "Connect in Telegram".
  3. Verify Telegram shows "Connected".
  4. Test message sending, channel media loading, and video playback.
  5. Verify proxy stays active when the device screen is turned off and during Wi-Fi ↔ LTE transitions.
