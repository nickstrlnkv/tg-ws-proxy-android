# AI Agent Guide: Android Port of `tg-ws-proxy`

This document serves as the primary entry point and high-level guideline for AI agents (and human engineers) implementing the native Android version of **TG WS Proxy**.

---

## 1. Context and Project Goal

**Upstream Project (`Flowseal/tg-ws-proxy`):**
A desktop local MTProto proxy server written in Python that mitigates throttling, ISP filtering, and blocking of Telegram Data Centers (DCs) by tunneling MTProto traffic through secure WebSocket (WSS) connections to Telegram's official web gateways (`kws{dc}.web.telegram.org/apiws`) or via Cloudflare Workers.

**Our Goal:**
Build a standalone, battery-efficient, and user-friendly native **Android** application that:
1. Runs a local MTProto proxy server on `127.0.0.1:1443` (or a user-configured port).
2. Runs reliably in the background via an Android `ForegroundService` with an ongoing notification and automatic reconnection across network changes (Wi-Fi ↔ LTE).
3. Provides a one-tap "Connect in Telegram" button dispatching the deep link `tg://proxy?server=127.0.0.1&port=1443&secret=...`.
4. Implements all networking and cryptographic logic cleanly in **Kotlin** (recommended native stack) or **Go / Rust** (via NDK/gomobile).

---

## 2. Documentation Structure

All detailed specifications are organized under the `docs/android/` directory:

| Document | Purpose |
| :--- | :--- |
| **[`REQUIREMENTS.md`](docs/android/REQUIREMENTS.md)** | Environment requirements, technology stack, Android permissions, dependencies, and project directory layout. |
| **[`PROTOCOL_SPEC.md`](docs/android/PROTOCOL_SPEC.md)** | Exact byte-level protocol specifications: MTProto Obfuscation 2.0, relay handshake generation, AES-CTR key derivation, `MsgSplitter` packet framing, and Telegram WebSocket endpoints. |
| **[`ARCHITECTURE.md`](docs/android/ARCHITECTURE.md)** | Android application architecture: UI (Jetpack Compose), Background Service, local TCP server, WebSocket client, state management, and power management. |
| **[`IMPLEMENTATION_STEPS.md`](docs/android/IMPLEMENTATION_STEPS.md)** | Step-by-step roadmap with an actionable checklist for the agent across development phases. |
| **[`UPSTREAM_SYNC.md`](docs/android/UPSTREAM_SYNC.md)** | Guide on how to monitor, diff, and port upstream changes from `Flowseal/tg-ws-proxy`. |

---

## 3. Key Architectural Principles

1. **Do Not Bundle a Heavy Python Runtime on Android:**  
   The original Python codebase is the reference specification. On Android, the app must be written in **Kotlin** (Coroutines + OkHttp + Java Crypto) or use a native core compiled in **Go** (`gomobile`) / **Rust** (`cargo-ndk`). Pure Kotlin is preferred because it eliminates ABI compilation overhead and multi-architecture complexities (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

2. **Android Lifecycle & Background Execution:**  
   Network sockets must not be terminated when the screen turns off. Implement a `ForegroundService` (type `dataSync` or `specialUse`) with an ongoing notification and request battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

3. **Local Loopback Communication:**  
   The proxy listens on `127.0.0.1`. Other apps running on the same device (such as Telegram) have unrestricted access to loopback ports on Android.

4. **End-to-End Privacy for Telegram:**  
   The proxy does not decrypt user messages or MTProto session data. The data remains encrypted end-to-end between Telegram client and Telegram DCs. The proxy merely removes the outer obfuscation transport layer, inspects the DC ID, and wraps the payload inside a WebSocket stream.

---

## 4. How to Get Started

1. Read **[`docs/android/PROTOCOL_SPEC.md`](docs/android/PROTOCOL_SPEC.md)** to understand byte-level framing, offsets, and cipher initialization.
2. Review **[`docs/android/REQUIREMENTS.md`](docs/android/REQUIREMENTS.md)** for dependencies and Gradle configuration.
3. Follow the phases in **[`docs/android/IMPLEMENTATION_STEPS.md`](docs/android/IMPLEMENTATION_STEPS.md)**: initialize the project in the `android/` subfolder, implement the core protocol with unit tests, build the background service, and assemble the UI.
