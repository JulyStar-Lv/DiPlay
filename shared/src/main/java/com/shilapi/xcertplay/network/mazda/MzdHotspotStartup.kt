package com.shilapi.xcertplay.network.mazda

import com.shilapi.xcertplay.network.*

import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import java.io.InterruptedIOException

/** Never include a system hotspot password in diagnostics. */
internal data class MzdHotspotCredentials(val ssid: String, val passphrase: String, val security: ManualHotspotSecurity) {
    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) { "Invalid car hotspot credentials" }
        require((security == ManualHotspotSecurity.OPEN) == passphrase.isEmpty()) { "Invalid car hotspot security" }
    }

    override fun toString() = "MzdHotspotCredentials(ssid=$ssid, passphrase=<redacted>, security=$security)"
}

internal data class MzdHotspotRadio(
    val enabled: Boolean? = null,
    val frequencies: List<Int> = emptyList(),
) {
    fun fiveGhzReady() = enabled == true && frequencies.size == 1 &&
        frequencies.all { it in 5150..5895 && wifiFrequencyMhzToChannel(it) != null }
}

/** Request 5 GHz only, then verify the live AP; a successful setter is not readiness. */
internal class MzdHotspotStartup<C : Any>(
    private val port: Port<C>,
    private val cancelled: () -> Boolean,
    private val now: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    interface Port<C> {
        fun read(): C
        fun credentials(config: C): MzdHotspotCredentials
        fun fiveGhz(config: C): C
        fun write(config: C): Boolean
        fun radio(): MzdHotspotRadio
        fun start()
        fun stop()
    }

    fun prepare(deadline: Long): MzdHotspotCredentials {
        fun checkActive() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Car hotspot startup cancelled")
            if (now() >= deadline) throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY, "Timed out waiting for the 5 GHz car hotspot")
        }
        fun await(condition: () -> Boolean) {
            while (true) {
                checkActive()
                if (condition()) { checkActive(); return }
                pause(minOf(100L, ((deadline - now()) / 1_000_000).coerceAtLeast(1)))
            }
        }
        checkActive()
        val original = port.read()
        val credentials = port.credentials(original)
        await { port.radio().let { it.enabled != null && (it.enabled == false || it.frequencies.isNotEmpty()) } }
        val initial = port.radio()
        // Reuse an already-running 5 GHz AP without disconnecting any clients.
        if (initial.fiveGhzReady()) return credentials
        // Require a copy of the full configuration; never rebuild from defaults.
        val target = port.fiveGhz(original)
        check(port.credentials(target) == credentials) { "Hotspot copy changed credentials" }
        var touched = false
        var owned = original
        try {
            checkActive()
            check(port.read() == original) { "Car hotspot configuration changed during startup" }
            if (initial.enabled == true) {
                touched = true
                port.stop()
                await { port.radio().enabled == false }
            }
            checkActive()
            check(port.read() == original) { "Car hotspot configuration changed before applying 5 GHz" }
            if (target != original) {
                touched = true
                owned = target // A setter may mutate even when it reports failure.
                check(port.write(target)) { "Car rejected the 5 GHz hotspot configuration" }
                val saved = port.read()
                check(port.credentials(saved) == credentials) { "Car hotspot credentials changed after applying 5 GHz" }
                owned = saved // Some firmware normalizes the saved band mask.
            }
            checkActive()
            touched = true
            port.start()
            await {
                val radio = port.radio()
                if (radio.enabled == true && radio.frequencies.isNotEmpty() && !radio.fiveGhzReady()) {
                    throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "Car hotspot started outside 5 GHz; check the car Wi-Fi and country settings")
                }
                radio.fiveGhzReady()
            }
            check(port.credentials(port.read()) == credentials) { "Car hotspot credentials changed while starting" }
            return credentials
        } catch (failure: Exception) {
            if (touched) {
                // Cleanup gets its own short deadline, including after cancellation.
                val interrupted = Thread.interrupted() || failure is InterruptedException
                try {
                    val recoveryDeadline = now() + 3_000_000_000L
                    val current = port.read()
                    // A rejected/throwing setter may leave the original unchanged.
                    check(current == owned || current == original) { "Hotspot changed externally; recovery did not overwrite it" }
                    owned = current
                    port.stop()
                    while (port.radio().enabled != false) {
                        check(now() < recoveryDeadline) { "Hotspot recovery timed out" }
                        pause(50)
                    }
                    check(port.read() == owned) { "Hotspot changed externally during recovery" }
                    if (owned != original) check(port.write(original)) { "Could not restore car hotspot configuration" }
                    check(port.read() == original) { "Car hotspot restore readback failed" }
                    if (initial.enabled == true) port.start()
                } catch (recovery: Exception) {
                    failure.addSuppressed(recovery)
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
            throw failure
        }
    }
}
