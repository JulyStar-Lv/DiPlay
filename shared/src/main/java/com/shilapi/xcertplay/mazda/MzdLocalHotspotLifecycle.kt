package com.shilapi.xcertplay.mazda

import java.io.IOException

/** Shared across controller instances: reservation.close() only requests an asynchronous stop. */
internal class MzdLocalHotspotLifecycle(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    internal class Ticket {
        var closed = false
        var pending = false
        var issued = false
        var stopped = false
        var requestStateVersion = 0L
        var closedAt = 0L
        @Volatile var failureReason: Int? = null
    }

    private val lock = Object()
    private var owner: Ticket? = null
    private var state: Int? = null
    private var stateVersion = 0L
    private var stateAt = 0L

    fun stateChanged(value: Int) = synchronized(lock) {
        state = value
        stateVersion++
        stateAt = nowMillis()
        lock.notifyAll()
    }

    fun acquire(timeoutMillis: Long, cancelled: () -> Boolean): Ticket = synchronized(lock) {
        val deadline = nowMillis() + minOf(timeoutMillis, STOP_TIMEOUT_MILLIS)
        while (true) {
            if (cancelled()) throw IOException("Mazda local hotspot start cancelled")
            val previous = owner
            if (previous == null || previous.closed && !previous.pending && (
                    !previous.issued || previous.failureReason?.let { it != 2 } == true ||
                        state == AP_DISABLED && stateVersion > previous.requestStateVersion &&
                        nowMillis() - maxOf(stateAt, previous.closedAt) >= STOP_SETTLE_MILLIS
                )
            ) {
                return@synchronized Ticket().also { owner = it }
            }
            val remaining = deadline - nowMillis()
            if (remaining <= 0) throw IOException("Mazda local hotspot did not confirm shutdown; refusing an overlapping request")
            try {
                lock.wait(minOf(remaining, 100L))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Mazda local hotspot shutdown", interrupted)
            }
        }
        error("unreachable")
    }

    fun begin(ticket: Ticket) = synchronized(lock) {
        if (owner !== ticket || ticket.closed) throw IOException("Mazda local hotspot start cancelled")
        ticket.issued = true
        ticket.pending = true
        ticket.requestStateVersion = stateVersion
    }

    fun resolved(ticket: Ticket, failureReason: Int? = null) = synchronized(lock) {
        if (!ticket.pending) return@synchronized
        ticket.pending = false
        ticket.failureReason = failureReason
        lock.notifyAll()
    }

    fun close(ticket: Ticket) = synchronized(lock) {
        if (!ticket.closed) {
            ticket.closed = true
            ticket.closedAt = nowMillis()
        }
        lock.notifyAll()
    }

    fun stopped(ticket: Ticket) = synchronized(lock) {
        ticket.stopped = true
        lock.notifyAll()
    }

    fun isActive(ticket: Ticket): Boolean = synchronized(lock) {
        owner === ticket && ticket.issued && !ticket.closed && !ticket.pending && !ticket.stopped &&
            ticket.failureReason == null && (state == null || state == AP_ENABLED)
    }

    companion object {
        // WifiManager.WIFI_AP_STATE_DISABLED is SystemApi; this is the LOHS callback's state.
        private const val AP_DISABLED = 11
        private const val AP_ENABLED = 13
        private const val STOP_SETTLE_MILLIS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
