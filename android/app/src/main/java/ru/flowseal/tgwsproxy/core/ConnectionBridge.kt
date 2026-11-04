package ru.flowseal.tgwsproxy.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class ConnectionBridge(
    private val clientSocket: Socket,
    private val candidateSecrets: List<ByteArray>,
    private val customWorkerDomains: List<String> = emptyList()
) {

    constructor(
        clientSocket: Socket,
        secret: ByteArray,
        customWorkerDomains: List<String> = emptyList()
    ) : this(
        clientSocket = clientSocket,
        candidateSecrets = listOf(
            secret,
            MtprotoCrypto.hexToBytes("ee112233445566778899aabbccddeeff"),
            MtprotoCrypto.hexToBytes("102030405060708090a0b0c0d0e0f011")
        ).distinctBy { MtprotoCrypto.bytesToHex(it) },
        customWorkerDomains = customWorkerDomains
    )

    companion object {
        private const val TAG = "TGWSProxy"
    }

    suspend fun run() {
        val peer = clientSocket.remoteSocketAddress?.toString() ?: "unknown"
        Log.i(TAG, "[$peer] Client connected")
        StatsManager.connectionOpened()
        var wsClient: WsClient? = null

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.soTimeout = 15000 // 15-second handshake timeout

            val inputStream = clientSocket.getInputStream()
            val outputStream = clientSocket.getOutputStream()

            // 1. Read exactly 64 bytes of MTProto handshake
            val handshake = readExact(inputStream, Constants.HANDSHAKE_LEN)
            if (handshake == null) {
                Log.w(TAG, "[$peer] Incomplete handshake (<64 bytes)")
                return
            }

            Log.i(TAG, "[$peer] Received 64-byte handshake: ${MtprotoCrypto.bytesToHex(handshake)}")

            // Check if Telegram attempted TLS record
            if (handshake[0] == 0x16.toByte()) {
                Log.e(TAG, "[$peer] Detected TLS Record (0x16)! Telegram is sending Fake-TLS.")
                return
            }

            // 2. Validate handshake and derive parameters against candidate secrets
            val handshakeResult = MtprotoCrypto.tryHandshakeWithSecrets(handshake, candidateSecrets)
            if (handshakeResult == null) {
                Log.w(TAG, "[$peer] Handshake validation failed for all candidate secrets!")
                return
            }

            val dcId = handshakeResult.dcId
            val isMedia = handshakeResult.isMedia
            val protoTag = handshakeResult.protoTag
            val clientDecPrekeyIv = handshakeResult.clientDecPrekeyIv
            val activeSecret = handshakeResult.matchedSecret

            val dcIdx: Short = if (isMedia) (-dcId).toShort() else dcId.toShort()
            val protoInt = when {
                protoTag.contentEquals(Constants.PROTO_TAG_ABRIDGED) -> Constants.PROTO_ABRIDGED_INT
                protoTag.contentEquals(Constants.PROTO_TAG_INTERMEDIATE) -> Constants.PROTO_INTERMEDIATE_INT
                else -> Constants.PROTO_PADDED_INTERMEDIATE_INT
            }

            Log.i(TAG, "[$peer] Handshake verified: DC$dcId (media=$isMedia, protoTag=0x${Integer.toHexString(protoInt)}) with secret ${MtprotoCrypto.bytesToHex(activeSecret)}")

            // 3. Generate relay init and set up crypto streams
            val relayInit = MtprotoCrypto.generateRelayInit(protoTag, dcIdx)
            val ctx = MtprotoCrypto.buildCryptoCtx(clientDecPrekeyIv, activeSecret, relayInit)

            // 4. Try candidate URLs
            val candidateUrls = FallbackManager.getCandidateUrls(dcId, isMedia, customWorkerDomains)
            var connected = false

            for (url in candidateUrls) {
                val candidateClient = WsClient()
                if (candidateClient.connect(url, relayInit)) {
                    connected = true
                    wsClient = candidateClient
                    StatsManager.wsConnected()
                    DomainBalancer.markDomainSuccess(dcId, url)
                    Log.i(TAG, "[$peer] Upstream WebSocket established to $url")
                    break
                } else {
                    candidateClient.close()
                }
            }

            if (!connected) {
                Log.e(TAG, "[$peer] All candidate WebSocket endpoints failed for DC$dcId")
                return
            }

            // 5. Bidirectional bridge
            clientSocket.soTimeout = 0 // Remove timeout during active stream
            val splitter = MsgSplitter(protoInt)
            runBridgeLoops(inputStream, outputStream, wsClient!!, ctx, splitter, peer)

        } catch (e: Exception) {
            Log.e(TAG, "[$peer] Session error: ${e.message}", e)
        } finally {
            StatsManager.connectionClosed()
            try {
                wsClient?.close()
            } catch (_: Exception) {}
            try {
                clientSocket.close()
            } catch (_: Exception) {}
            Log.i(TAG, "[$peer] Session closed")
        }
    }

    private suspend fun runBridgeLoops(
        inputStream: InputStream,
        outputStream: OutputStream,
        wsClient: WsClient,
        ctx: CryptoCtx,
        splitter: MsgSplitter,
        peer: String
    ) {
        val bridgeJob = Job()
        val scope = CoroutineScope(Dispatchers.IO + bridgeJob)

        // TCP -> WS worker
        val tcpToWsJob = scope.launch {
            val buffer = ByteArray(65536)
            try {
                while (isActive) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) {
                        val tail = splitter.flush()
                        for (part in tail) {
                            wsClient.send(part)
                        }
                        break
                    }

                    StatsManager.addBytesUp(read.toLong())
                    val chunk = buffer.copyOfRange(0, read)
                    val plain = ctx.cltDec.update(chunk) ?: ByteArray(0)
                    val reEncrypted = ctx.tgEnc.update(plain) ?: ByteArray(0)

                    val parts = splitter.split(reEncrypted, plain)
                    for (part in parts) {
                        wsClient.send(part)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "[$peer] TCP->WS closed: ${e.message}")
            } finally {
                wsClient.close()
            }
        }

        // WS -> TCP worker
        val wsToTcpJob = scope.launch {
            try {
                while (isActive) {
                    val msg = wsClient.receive() ?: break
                    val plain = ctx.tgDec.update(msg) ?: ByteArray(0)
                    val forClient = ctx.cltEnc.update(plain) ?: ByteArray(0)

                    outputStream.write(forClient)
                    outputStream.flush()
                    StatsManager.addBytesDown(forClient.size.toLong())
                }
            } catch (e: Exception) {
                Log.d(TAG, "[$peer] WS->TCP closed: ${e.message}")
            } finally {
                try {
                    clientSocket.close()
                } catch (_: Exception) {}
            }
        }

        // Wait until either loop terminates
        tcpToWsJob.join()
        wsToTcpJob.join()
        bridgeJob.cancel()
    }

    private fun readExact(inputStream: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = inputStream.read(buffer, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return buffer
    }
}
