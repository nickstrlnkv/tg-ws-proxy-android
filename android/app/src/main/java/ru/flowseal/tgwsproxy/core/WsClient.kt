package ru.flowseal.tgwsproxy.core

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.Closeable
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WsClient(
    private val client: OkHttpClient = defaultClient
) : Closeable {

    companion object {
        private const val TAG = "TGWSProxy"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(Duration.ofSeconds(30))
                .connectTimeout(Duration.ofMillis(3500))
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ofSeconds(10))
                .build()
        }
    }

    private var webSocket: WebSocket? = null
    private val incomingChannel = Channel<ByteArray>(Channel.BUFFERED)
    private var isConnected = false

    suspend fun connect(url: String, relayInit: ByteArray): Boolean = suspendCoroutine { cont ->
        Log.i(TAG, "Attempting WebSocket connection to: $url")
        val request = Request.Builder()
            .url(url)
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:130.0) Gecko/130.0 Firefox/130.0")
            .build()

        var resumed = false

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully to $url (code=${response.code})")
                isConnected = true
                webSocket.send(relayInit.toByteString())
                if (!resumed) {
                    resumed = true
                    cont.resume(true)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incomingChannel.trySend(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing from server ($url): $code / $reason")
                webSocket.close(1000, null)
                incomingChannel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed ($url): $code / $reason")
                isConnected = false
                incomingChannel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed ($url): ${t.message} (response=${response?.code})", t)
                isConnected = false
                incomingChannel.close(t)
                if (!resumed) {
                    resumed = true
                    cont.resume(false)
                }
            }
        })
    }

    fun send(bytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        return ws.send(bytes.toByteString())
    }

    suspend fun receive(): ByteArray? {
        return try {
            incomingChannel.receive()
        } catch (_: ClosedReceiveChannelException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        isConnected = false
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (_: Exception) {}
        webSocket = null
        incomingChannel.close()
    }
}
