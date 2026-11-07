package com.shilapi.xcertplay.mazda

import android.net.wifi.WifiManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import java.io.IOException

/** Retry a transient framework rejection before the UI offers the manual car hotspot. */
internal class MzdLocalHotspotManager(
    private val lifecycle: MzdLocalHotspotLifecycle,
    private val diagnostic: (String) -> Unit,
    private val create: (MzdLocalHotspotLifecycle.Ticket) -> WirelessHotspotManager,
) : WirelessHotspotManager {
    private val lock = Any()
    @Volatile private var closed = false
    private var ticket: MzdLocalHotspotLifecycle.Ticket? = null
    @Volatile private var delegate: WirelessHotspotManager? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        require(timeoutMillis > 0)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        fun remaining() = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        repeat(2) { attempt ->
            val lease = lifecycle.acquire(remaining()) { closed }
            val active = synchronized(lock) {
                if (closed) {
                    lifecycle.close(lease)
                    throw IOException("Mazda local hotspot start cancelled")
                }
                try {
                    create(lease).also { ticket = lease; delegate = it }
                } catch (failure: Exception) {
                    lifecycle.close(lease)
                    throw failure
                }
            }
            try {
                val budget = remaining()
                if (budget <= 0) throw IOException("Mazda local hotspot startup deadline exceeded")
                return active.start(budget)
            } catch (failure: Exception) {
                lifecycle.close(lease)
                active.close()
                if (closed || Thread.currentThread().isInterrupted || attempt != 0 ||
                    lease.failureReason != WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC || remaining() <= 0
                ) throw failure
                diagnostic("Mazda local hotspot: transient error 2; waiting for LOHS shutdown before one retry")
            }
        }
        error("unreachable")
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            ticket?.let(lifecycle::close)
            delegate
        }
        // State callbacks continue on the main looper; the next start waits on its worker.
        active?.close()
    }

    override fun validateReady() {
        val active = synchronized(lock) {
            if (closed || ticket?.let(lifecycle::isActive) != true) {
                throw IOException("Mazda local hotspot is no longer active")
            }
            delegate
        }
        active?.validateReady()
    }
    override fun onCarPlayConfirmed() { delegate?.onCarPlayConfirmed() }
    override fun connectionDiagnosticSnapshot(): String = delegate?.connectionDiagnosticSnapshot() ?: "association=not_exposed"
}
