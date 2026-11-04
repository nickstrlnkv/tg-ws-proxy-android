package ru.flowseal.tgwsproxy.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ProxyStats(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val activeConnections: Int = 0,
    val totalConnections: Long = 0,
    val wsConnections: Long = 0,
    val fallbackConnections: Long = 0
)

object StatsManager {
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)
    private val _activeConnections = AtomicInteger(0)
    private val _totalConnections = AtomicLong(0)
    private val _wsConnections = AtomicLong(0)
    private val _fallbackConnections = AtomicLong(0)

    private val _statsFlow = MutableStateFlow(ProxyStats())
    val statsFlow: StateFlow<ProxyStats> = _statsFlow.asStateFlow()

    fun addBytesUp(bytes: Long) {
        _bytesUp.addAndGet(bytes)
        updateFlow()
    }

    fun addBytesDown(bytes: Long) {
        _bytesDown.addAndGet(bytes)
        updateFlow()
    }

    fun connectionOpened() {
        _totalConnections.incrementAndGet()
        _activeConnections.incrementAndGet()
        updateFlow()
    }

    fun connectionClosed() {
        _activeConnections.decrementAndGet()
        updateFlow()
    }

    fun wsConnected() {
        _wsConnections.incrementAndGet()
        updateFlow()
    }

    fun fallbackConnected() {
        _fallbackConnections.incrementAndGet()
        updateFlow()
    }

    fun reset() {
        _bytesUp.set(0)
        _bytesDown.set(0)
        _activeConnections.set(0)
        _totalConnections.set(0)
        _wsConnections.set(0)
        _fallbackConnections.set(0)
        updateFlow()
    }

    private fun updateFlow() {
        _statsFlow.value = ProxyStats(
            bytesUp = _bytesUp.get(),
            bytesDown = _bytesDown.get(),
            activeConnections = _activeConnections.get().coerceAtLeast(0),
            totalConnections = _totalConnections.get(),
            wsConnections = _wsConnections.get(),
            fallbackConnections = _fallbackConnections.get()
        )
    }
}
