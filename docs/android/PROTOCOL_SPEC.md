# Byte-Level Protocol Specification

This document provides the exact formulas, byte offsets, and networking algorithms for the MTProto <-> WebSocket translation bridge, extracted from `proxy/tg_ws_proxy.py`, `proxy/bridge.py`, and `proxy/utils.py`.

---

## 1. Protocol Constants

| Name | Value | Description |
| :--- | :--- | :--- |
| `HANDSHAKE_LEN` | `64` | Total byte length of MTProto obfuscation init packet |
| `SKIP_LEN` | `8` | Offset to skip before reading prekey and IV |
| `PREKEY_LEN` | `32` | Byte length of the AES prekey source |
| `IV_LEN` | `16` | Byte length of the initialization vector (IV) |
| `PROTO_TAG_POS` | `56` | Byte offset of the protocol tag (4 bytes) |
| `DC_IDX_POS` | `60` | Byte offset of the DC index (2 bytes, signed int16 little-endian) |

### Protocol Tags:
* **Abridged:** `0xefefefef` (`b"\xef\xef\xef\xef"`)
* **Intermediate:** `0xeeeeeeee` (`b"\xee\xee\xee\xee"`)
* **Padded Intermediate / Secure:** `0xdddddddd` (`b"\xdd\xdd\xdd\xdd"`)

### Reserved Byte Sequences (Rejected when generating relay handshake):
* First byte must not be: `0xef`
* First 4 bytes must not match: `"HEAD"`, `"POST"`, `"GET "`, `0xeeeeeeee`, `0xdddddddd`, `0x16030102`, `"OPTI"`
* Bytes 4 through 7 must not be all zeros: `0x00000000`

---

## 2. Inbound Telegram Client Handshake (`_try_handshake`)

When Telegram connects to the local proxy socket (`127.0.0.1:1443`), it sends exactly 64 bytes of `handshake`.

```
[0..7: 8 random bytes] [8..39: 32 bytes prekey] [40..55: 16 bytes iv] [56..59: proto] [60..61: dc_idx] [62..63: extra]
```

### Algorithm:
1. Extract `client_dec_prekey_iv = handshake[8..56]` (length 48 bytes).
   * `dec_prekey = client_dec_prekey_iv[0..32]` (32 bytes)
   * `dec_iv = client_dec_prekey_iv[32..48]` (16 bytes)
2. Derive the decryption key:
   $$\text{dec\_key} = \text{SHA-256}(\text{dec\_prekey} + \text{secret})$$
3. Initialize cipher `AES-CTR` (256-bit with SHA-256 output, or 128-bit):
   * Key: `dec_key` (32 bytes)
   * Initial IV: `dec_iv` (16 bytes, big-endian)
4. Decrypt the entire 64-byte `handshake` buffer.
5. Validate bytes `[56..60]` of the decrypted buffer:
   * If they **do not match** `0xefefefef`, `0xeeeeeeee`, or `0xdddddddd`, the handshake is invalid (wrong secret or corrupt packet). Discard connection.
6. Extract `dc_idx`:
   * Read bytes `[60..62]` as **signed little-endian int16**:
   * $\text{dc\_id} = |\text{dc\_idx}|$
   * $\text{is\_media} = (\text{dc\_idx} < 0)$

---

## 3. Outbound Relay Handshake Generation (`_generate_relay_init`)

To initiate communication with the official Telegram WebSocket gateway, a new 64-byte obfuscation packet is crafted:

1. Generate 64 cryptographically secure random bytes `rnd`:
   * Repeat until validation passes (first byte $\neq 0xef$, starts don't match HTTP methods, bytes 4..7 $\neq 0$).
2. Extract `enc_key = rnd[8..40]` and `enc_iv = rnd[40..56]`.
3. Create an AES-CTR encryptor (`enc_key`, `enc_iv`).
4. Construct the unencrypted tail (`tail_plain` of 8 bytes):
   * `proto_tag` (4 bytes) + `dc_idx` (int16 little-endian, 2 bytes) + `random(2 bytes)`.
5. Encrypt `rnd` with the cipher: `encrypted_full = encryptor.update(rnd)`.
6. Compute the keystream for the tail `[56..64]`:
   $$\text{keystream\_tail}[i] = \text{encrypted\_full}[56+i] \oplus \text{rnd}[56+i] \quad (i = 0..7)$$
7. Encrypt the tail:
   $$\text{encrypted\_tail}[i] = \text{tail\_plain}[i] \oplus \text{keystream\_tail}[i] \quad (i = 0..7)$$
8. Replace slice `[56..64]` in `rnd` with `encrypted_tail`.
9. The resulting 64-byte array `rnd` is the `relay_init` payload.

---

## 4. Setting Up the 4 AES-CTR Streams (`CryptoCtx`)

In MTProto Obfuscation 2.0, each direction uses an independent AES-CTR counter.

```
Telegram Client <---(clt_enc/clt_dec)---> [Our Proxy] <---(tg_enc/tg_dec)---> Telegram WebSocket DC
```

### 4.1. Client Channel (`clt_dec` and `clt_enc`):
* **`clt_dec` (decrypts incoming data from Telegram client):**
  * Key: `SHA256(client_dec_prekey_iv[0..32] + secret)`
  * IV: `client_dec_prekey_iv[32..48]`
  * ⚠️ **CRITICAL:** Fast-forward the counter by 64 bytes: call `cipher.update(bytes_64_zeros)`.
* **`clt_enc` (encrypts outgoing data to Telegram client):**
  * Reverse the 48-byte array: `clt_enc_prekey_iv = reverse(client_dec_prekey_iv)`
  * Key: `SHA256(clt_enc_prekey_iv[0..32] + secret)`
  * IV: `clt_enc_prekey_iv[32..48]`

### 4.2. Upstream Telegram DC Channel (`tg_enc` and `tg_dec`):
On the Telegram server side, raw obfuscation without secret hashing is used:
* **`tg_enc` (encrypts outgoing data to Telegram WebSocket):**
  * Key: `relay_init[8..40]`
  * IV: `relay_init[40..56]`
  * ⚠️ **CRITICAL:** Fast-forward the counter by 64 bytes: call `cipher.update(bytes_64_zeros)`.
* **`tg_dec` (decrypts incoming data from Telegram WebSocket):**
  * Reverse the 48-byte slice: `relay_dec_prekey_iv = reverse(relay_init[8..56])`
  * Key: `relay_dec_prekey_iv[0..32]`
  * IV: `relay_dec_prekey_iv[32..48]`

---

## 5. WebSocket Connection to Telegram DC

### 5.1. Addressing (URL)
For the target `dc_id` (1–5):
* If `dc == 203`, map to `dc = 2`.
* Non-media traffic (`!is_media`):
  * URL: `wss://kws{dc}.web.telegram.org/apiws`
* Media traffic (`is_media`):
  * Primary URL: `wss://kws{dc}-1.web.telegram.org/apiws`
  * Fallback URL: `wss://kws{dc}.web.telegram.org/apiws`

### 5.2. WebSocket Headers
* `Sec-WebSocket-Protocol: binary`
* `User-Agent: Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0`

### 5.3. WSS Handshake Frame
Immediately after the WebSocket connection is established (`onOpen`):
* Send the initial **Binary Frame** containing the 64-byte `relay_init`.

---

## 6. Packet Framing (`MsgSplitter`)

Telegram Web WebSocket requires that **every individual MTProto transport packet be sent as a distinct WebSocket binary frame**. Because TCP is a continuous stream that may concatenate or fragment packets, `MsgSplitter` reconstructs message boundaries:

### 6.1. Abridged Protocol (`0xefefefef`)
* Inspect the first byte of the decrypted packet (`byte[0]`):
  * If `byte[0] == 0x7f` or `0xff`:
    * Length header size: 4 bytes.
    * Payload length: `(byte[1] | (byte[2] << 8) | (byte[3] << 16)) * 4` bytes.
    * Total packet size: $4 + \text{payload\_len}$.
  * Otherwise:
    * Length header size: 1 byte.
    * Payload length: `(byte[0] & 0x7f) * 4` bytes.
    * Total packet size: $1 + \text{payload\_len}$.

### 6.2. Intermediate Protocol (`0xeeeeeeee` and `0xdddddddd`)
* Length header size: 4 bytes (uint32 little-endian).
* Payload length: `(uint32_le(byte[0..4]) & 0x7fffffff)` bytes.
* Total packet size: $4 + \text{payload\_len}$.

---

## 7. Bidirectional Bridge Loop (Re-encryption)

### Client -> Telegram DC
1. Read byte chunk from client TCP socket (`reader.read()`).
2. Decrypt to plaintext: `plain = clt_dec.update(chunk)`.
3. Pass `chunk` and `plain` into `MsgSplitter.split()`.
4. For each complete MTProto packet returned:
   * Encrypt for Telegram: `ws_payload = tg_enc.update(packet_plain)`.
   * Send as WebSocket binary message: `webSocket.send(ws_payload)`.

### Telegram DC -> Client
1. On WebSocket message received (`onMessage(bytes)`):
2. Decrypt upstream data: `plain = tg_dec.update(bytes)`.
3. Encrypt for client: `client_payload = clt_enc.update(plain)`.
4. Write directly to client TCP socket: `socket.getOutputStream().write(client_payload)`.
