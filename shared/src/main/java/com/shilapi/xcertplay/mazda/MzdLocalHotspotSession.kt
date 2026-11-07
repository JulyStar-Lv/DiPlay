package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import java.io.Closeable
import java.io.IOException

/** Keep a ready reservation across controller retries; the connection flow owns its final release. */
internal class MzdLocalHotspotSession(
    private val validate: (WirelessHotspotInfo) -> Unit,
    private val create: (MzdLocalHotspot.Credentials, (String) -> Unit) -> WirelessHotspotManager,
) : Closeable {
    private class Entry(val credentials: MzdLocalHotspot.Credentials) {
        lateinit var manager: WirelessHotspotManager
        var info: WirelessHotspotInfo? = null
        var owner: Any? = null
        @Volatile var diagnostic: ((String) -> Unit)? = null
    }

    private val lock = Any()
    private var active: Entry? = null

    fun manager(credentials: MzdLocalHotspot.Credentials, diagnostic: (String) -> Unit): WirelessHotspotManager =
        object : WirelessHotspotManager {
            private var closed = false
            private var entry: Entry? = null

            override fun start(timeoutMillis: Long): WirelessHotspotInfo {
                require(timeoutMillis > 0)
                val selected = synchronized(lock) {
                    if (closed) throw IOException("Mazda local hotspot start cancelled")
                    check(entry == null) { "Mazda local hotspot handle already started" }
                    val previous = active
                    val reusable = previous?.info?.let { info ->
                        previous.credentials == credentials && runCatching {
                            previous.manager.validateReady()
                            validate(info)
                        }.isSuccess
                    } == true
                    if (!reusable && previous != null) {
                        active = null
                        previous.diagnostic = null
                        previous.manager.close()
                    }
                    (if (reusable) previous!! else Entry(credentials).also { next ->
                        next.manager = create(credentials) { next.diagnostic?.invoke(it) }
                        active = next
                    }).also { it.owner = this; it.diagnostic = diagnostic; entry = it }
                }
                selected.info?.let {
                    diagnostic("Mazda local hotspot: reusing ready 5 GHz hotspot; keeping reservation and interface")
                    return synchronized(lock) {
                        if (closed || active !== selected) throw IOException("Mazda local hotspot start cancelled")
                        it
                    }
                }
                try {
                    val info = selected.manager.start(timeoutMillis)
                    if (info.ssid != credentials.ssid || info.passphrase != credentials.password) {
                        throw IOException("Mazda local hotspot credentials do not match the requested configuration")
                    }
                    return synchronized(lock) {
                        if (closed || active !== selected) throw IOException("Mazda local hotspot start cancelled")
                        info.also { selected.info = it }
                    }
                } catch (failure: Exception) {
                    synchronized(lock) {
                        if (active === selected) active = null
                        selected.diagnostic = null
                        selected.manager.close()
                    }
                    throw failure
                }
            }

            override fun close() = synchronized(lock) {
                if (closed) return@synchronized
                closed = true
                val selected = entry ?: return@synchronized
                if (selected.owner !== this) return@synchronized
                selected.diagnostic = null
                selected.owner = null
                // An incomplete start cannot be reused; cancel it and close any late reservation.
                if (active === selected && selected.info == null) {
                    active = null
                    selected.manager.close()
                }
            }

            private fun delegate(): Entry = synchronized(lock) {
                entry?.takeIf { !closed && active === it && it.owner === this }
                    ?: throw IOException("Mazda local hotspot handle is no longer active")
            }

            override fun validateReady() {
                val selected = delegate()
                selected.manager.validateReady()
                selected.info?.let(validate)
            }

            override fun onCarPlayConfirmed() { delegate().manager.onCarPlayConfirmed() }
            override fun connectionDiagnosticSnapshot(): String = synchronized(lock) {
                entry?.takeIf { !closed && active === it && it.owner === this }
                    ?.manager?.connectionDiagnosticSnapshot() ?: "association=not_exposed"
            }
        }

    override fun close() {
        val previous = synchronized(lock) {
            active.also { active = null; it?.diagnostic = null }
        }
        previous?.manager?.close()
    }
}
