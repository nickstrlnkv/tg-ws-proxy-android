package ru.flowseal.tgwsproxy.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

class MtprotoServer(
    private val port: Int,
    private val secret: ByteArray,
    private val customWorkerDomains: List<String> = emptyList()
) : Closeable {

    companion object {
        private const val TAG = "TGWSProxy"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var isRunning = false

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        if (isRunning) return

        Log.i(TAG, "Starting MtprotoServer on 127.0.0.1:$port ...")
        val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        socket.reuseAddress = true
        serverSocket = socket
        isRunning = true
        Log.i(TAG, "MtprotoServer bound successfully to ${socket.localSocketAddress}")

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                while (isRunning && isActive) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (e: SocketException) {
                        Log.i(TAG, "ServerSocket accept loop closed: ${e.message}")
                        break
                    }
                    Log.i(TAG, "Accepted client socket connection: ${clientSocket.remoteSocketAddress}")
                    launch {
                        ConnectionBridge(clientSocket, secret, customWorkerDomains).run()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in accept loop: ${e.message}", e)
            } finally {
                isRunning = false
            }
        }
    }

    override fun close() {
        Log.i(TAG, "Stopping MtprotoServer on port $port ...")
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    fun isAlive(): Boolean = isRunning && serverSocket?.isBound == true && serverSocket?.isClosed == false
}
