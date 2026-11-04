# Upstream Synchronization Guide

The upstream repository [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) may release updates periodically: adding new Telegram DC domains, improving Cloudflare bypass mechanisms, updating headers, or patching media download issues.

This document outlines how to keep the Android port synchronized with upstream developments.

---

## 1. File Mapping Table (Python -> Kotlin)

When reviewing changes in the `proxy/` folder of the original repository, use this mapping table to locate the corresponding logic in the Android project:

| Upstream `proxy/` (Python) | Android `android/` (Kotlin) | Responsibility |
| :--- | :--- | :--- |
| `proxy/utils.py` | `core/Constants.kt` | Telegram DC IP addresses (`DC_DEFAULT_IPS`), WebSocket hostnames (`ws_domains`), API endpoints (`/apiws`). |
| `proxy/tg_ws_proxy.py` | `core/MtprotoCrypto.kt`, `core/MtprotoServer.kt` | Client handshake verification (`_try_handshake`), relay init packet generation (`_generate_relay_init`), AES-CTR stream cipher setup. |
| `proxy/bridge.py` | `core/ConnectionBridge.kt`, `core/MsgSplitter.kt` | Packet framing (`MsgSplitter`), bidirectional data proxying, retry and backoff logic. |
| `proxy/raw_websocket.py` | `core/WsClient.kt` | WSS handshake, timeouts, binary frame transmission. |
| `proxy/cf_h2.py`, workers | `core/FallbackManager.kt` | Cloudflare Worker / CDN fallback channels. |

---

## 2. Git Workflow for Tracking Upstream

### Step 1. Add Upstream Remote (One-Time Setup)
```bash
git remote add upstream https://github.com/Flowseal/tg-ws-proxy.git
git fetch upstream
```

### Step 2. Fetch Latest Releases and Tags
```bash
git fetch upstream --tags
git tag -l
```

### Step 3. Inspect Networking Diff Only
You do **not** need to review desktop GUI changes (`windows.py`, `ui/`, `macos.py`). Focus strictly on changes inside the `proxy/` directory:

```bash
# Compare the last synced tag with upstream main for the proxy package
git diff v1.11.0 upstream/main -- proxy/
```

Typically, upstream changes involve only 15–40 lines of code (such as an updated DC IP or a tweaked WebSocket header). Port these changes to the corresponding Kotlin files in `android/`.

---

## 3. Automated Monitoring (GitHub Actions)

You can set up a GitHub Actions workflow at `.github/workflows/check-upstream.yml` to automatically poll for new upstream releases:

```yaml
name: Check Upstream Releases

on:
  schedule:
    # Run daily at 09:00 UTC
    - cron: '0 9 * * *'
  workflow_dispatch:

jobs:
  check-release:
    runs-on: ubuntu-latest
    steps:
      - name: Fetch latest release from Flowseal/tg-ws-proxy
        id: upstream
        run: |
          LATEST_TAG=$(curl -s https://api.github.com/repos/Flowseal/tg-ws-proxy/releases/latest | jq -r .tag_name)
          echo "tag=$LATEST_TAG" >> $GITHUB_OUTPUT

      - name: Notify or Create Issue if new release exists
        run: |
          echo "Latest upstream release is: ${{ steps.upstream.outputs.tag }}"
          # Optional: dispatch Telegram bot notification or create a GitHub Issue
```
