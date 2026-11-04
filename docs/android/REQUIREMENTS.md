# Android Application Requirements

This document outlines the technology stack, minimum and target OS versions, dependencies, required Android permissions, and recommended project structure.

---

## 1. Platform & SDK

* **Language:** Kotlin (1.9+ or 2.0+)
* **Build System:** Gradle (Kotlin DSL `build.gradle.kts`)
* **Minimum Android Version (Min SDK):** `26` (Android 8.0 Oreo) — provides full support for Java NIO, modern socket APIs, and covers 96%+ of active devices worldwide.
* **Target Android Version (Target SDK):** `34` or `35` (Android 14 / 15)
* **Compile SDK:** `34` / `35`
* **Java Compatibility:** Java 17

---

## 2. Recommended Libraries and Dependencies

### 2.1. Networking & Cryptography
1. **OkHttp (`com.squareup.okhttp3:okhttp:4.12.0`):**
   * High-performance built-in **WebSocket (WSS)** support over TLS.
   * Automatic HTTP/2 connection pooling.
   * Configurable timeouts, ping intervals, and socket recovery.
2. **Kotlin Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-core` and `kotlinx-coroutines-android`):**
   * Asynchronous, non-blocking TCP socket handling on `Dispatchers.IO`.
   * Each Telegram client connection is handled by a lightweight coroutine rather than an OS-level thread.
3. **Java Cryptography Architecture (JCA, built into Android):**
   * `Cipher.getInstance("AES/CTR/NoPadding")` — for MTProto Obfuscation 2.0 stream cipher.
   * `MessageDigest.getInstance("SHA-256")` — for handshake key derivation.
   * `SecureRandom` — for generating 64-byte obfuscation prekeys and nonces.

### 2.2. User Interface (UI)
1. **Jetpack Compose + Material 3:**
   * Modern declarative Android UI.
   * Responsive layout with Material You / dynamic theming (Dark / Light mode).
2. **Compose ViewModel & Lifecycle:**
   * `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0`
   * `androidx.lifecycle:lifecycle-runtime-compose:2.8.0`

### 2.3. Data Persistence
* **Jetpack DataStore (`androidx.datastore:datastore-preferences:1.1.1`):**
  * Persistent storage for user settings: local port (default `1443`), secret key (hex), optional Cloudflare Worker domains, and fallback settings.

---

## 3. Android System Permissions (`AndroidManifest.xml`)

The application requires the following permissions:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Network access for outbound WSS connections and local loopback socket -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Foreground service execution for continuous proxy operation -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    
    <!-- Foreground Service Type for Android 14+ (SDK 34+) -->
    <!-- dataSync or specialUse recommended -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <!-- Ongoing notification display (required on Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Battery optimization exemption request for uninterrupted background execution -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

</manifest>
```

---

## 4. Recommended Project Directory Layout

All Android files must reside in the `android/` directory in the repository root to avoid cluttering existing desktop/Python scripts:

```
tg-ws-proxy-android/
├── AGENTS.md
├── docs/
│   ├── android/
│   │   ├── REQUIREMENTS.md
│   │   ├── PROTOCOL_SPEC.md
│   │   ├── ARCHITECTURE.md
│   │   ├── IMPLEMENTATION_STEPS.md
│   │   └── UPSTREAM_SYNC.md
├── proxy/             # Original Python implementation (algorithm reference)
└── android/           # Android Studio / Gradle project
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle/
    ├── app/
    │   ├── build.gradle.kts
    │   ├── src/
    │   │   ├── main/
    │   │   │   ├── AndroidManifest.xml
    │   │   │   ├── java/ru/flowseal/tgwsproxy/
    │   │   │   │   ├── core/                  # Core networking engine
    │   │   │   │   │   ├── MtprotoServer.kt   # TCP ServerSocket on 127.0.0.1
    │   │   │   │   │   ├── ConnectionBridge.kt# Bi-directional TCP <-> WebSocket bridge
    │   │   │   │   │   ├── MtprotoCrypto.kt   # AES-CTR, SHA-256, handshake parser
    │   │   │   │   │   ├── MsgSplitter.kt     # Abridged/Intermediate packet framing
    │   │   │   │   │   ├── WsClient.kt        # OkHttp WebSocket client
    │   │   │   │   │   ├── FallbackManager.kt # Failover logic (Direct -> CF Worker)
    │   │   │   │   │   └── Constants.kt       # DC IPs, kws*.web.telegram.org domains
    │   │   │   │   ├── service/
    │   │   │   │   │   ├── ProxyForegroundService.kt # Android Background Service
    │   │   │   │   │   └── NotificationHelper.kt     # Persistent status notification
    │   │   │   │   ├── data/
    │   │   │   │   │   ├── PreferencesManager.kt     # DataStore configuration
    │   │   │   │   │   └── ProxyConfig.kt            # Configuration model
    │   │   │   │   └── ui/
    │   │   │   │       ├── MainActivity.kt
    │   │   │   │       ├── MainScreen.kt             # Jetpack Compose UI
    │   │   │   │       ├── MainViewModel.kt
    │   │   │   │       └── theme/
    │   │   │   └── res/
    │   │   └── test/
    │   │       └── java/ru/flowseal/tgwsproxy/core/
    │   │           ├── HandshakeCryptoTest.kt        # Crypto test vectors against Python
    │   │           └── MsgSplitterTest.kt            # Packet framing unit tests
```
