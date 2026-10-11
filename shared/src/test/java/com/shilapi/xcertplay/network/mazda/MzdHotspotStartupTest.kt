package com.shilapi.xcertplay.network.mazda

import com.shilapi.xcertplay.network.*

import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class MzdHotspotStartupTest {
    private val credentials = MzdHotspotCredentials("Car network", "car-password", ManualHotspotSecurity.WPA2)
    private data class Config(val credentials: MzdHotspotCredentials, val band: Int, val clients: Int = 3)

    private inner class Fake(var enabled: Boolean = false, band: Int = 1) : MzdHotspotStartup.Port<Config> {
        var config = Config(credentials, band)
        var actualFrequency = 5180
        var failStart = false
        var rejectFiveGhz = false
        var mutateRejectedWrite = false
        var noInfo = false
        var afterWrite: () -> Unit = {}
        var externalEditOnStart = false
        val actions = mutableListOf<String>()
        override fun read() = config
        override fun credentials(config: Config) = config.credentials
        override fun fiveGhz(config: Config) = config.copy(band = 2)
        override fun write(config: Config): Boolean {
            actions += "write:${config.band}"
            if (rejectFiveGhz && config.band == 2) {
                if (mutateRejectedWrite) this.config = config
                return false
            }
            this.config = config
            afterWrite()
            return true
        }
        override fun radio() = MzdHotspotRadio(enabled,
            if (!enabled || noInfo) emptyList() else listOf(actualFrequency))
        override fun start() {
            actions += "start"
            if (externalEditOnStart) config = config.copy(clients = 9)
            if (failStart) { failStart = false; error("Synthetic tethering failure") }
            enabled = true
        }
        override fun stop() { actions += "stop"; enabled = false }
    }

    private fun prepare(fake: Fake, cancelled: () -> Boolean = { false }): MzdHotspotCredentials {
        var nanos = 0L
        return MzdHotspotStartup(fake, cancelled, now = { nanos }, pause = { nanos += it * 1_000_000 })
            .prepare(1_000_000_000)
    }

    @Test fun startsFiveGhzFromOffWithExistingCredentialsAndClientLimits() {
        val fake = Fake()
        assertEquals(credentials, prepare(fake))
        assertEquals(Config(credentials, 2, 3), fake.config)
        assertEquals(listOf("write:2", "start"), fake.actions)
        assertTrue(fake.enabled)
    }

    @Test fun existingFiveGhzHotspotIsReusedWithoutRestartOrWrites() {
        val fake = Fake(enabled = true, band = 2)
        assertEquals(credentials, prepare(fake))
        assertTrue(fake.actions.isEmpty())
    }

    @Test fun runningTwoGhzHotspotIsStoppedBeforeConfigurationChanges() {
        val fake = Fake(enabled = true)
        fake.actualFrequency = 2412
        fake.afterWrite = { fake.actualFrequency = 5180 }
        assertEquals(credentials, prepare(fake))
        assertEquals(listOf("stop", "write:2", "start"), fake.actions)
    }

    @Test fun successfulStartCallbackWithoutActualFrequencyIsNotReady() {
        val fake = Fake()
        fake.noInfo = true
        assertThrows(WirelessStartupException::class.java) { prepare(fake) }
        assertEquals(Config(credentials, 1), fake.config)
        assertFalse(fake.enabled)
    }

    @Test fun actualTwoGhzDespiteFiveGhzConfigurationFailsAndRestoresOriginal() {
        val fake = Fake()
        fake.actualFrequency = 2412
        assertThrows(WirelessStartupException::class.java) { prepare(fake) }
        assertEquals(Config(credentials, 1), fake.config)
        assertFalse(fake.enabled)
        assertEquals(listOf("write:2", "start", "stop", "write:1"), fake.actions)
    }

    @Test fun cancellationBeforeStartingDoesNotTouchTheCar() {
        val fake = Fake()
        assertThrows(InterruptedIOException::class.java) { prepare(fake) { true } }
        assertTrue(fake.actions.isEmpty())
    }

    @Test fun cancellationAfterWritingRestoresConfigurationWithoutStartingFiveGhz() {
        val fake = Fake()
        var cancelled = false
        fake.afterWrite = { cancelled = true }
        assertThrows(InterruptedIOException::class.java) { prepare(fake) { cancelled } }
        assertEquals(Config(credentials, 1), fake.config)
        assertEquals(listOf("write:2", "stop", "write:1"), fake.actions)
    }

    @Test fun failedStartRestoresAnInitiallyRunningHotspot() {
        val fake = Fake(enabled = true)
        fake.actualFrequency = 2412
        fake.failStart = true
        assertThrows(IllegalStateException::class.java) { prepare(fake) }
        assertEquals(Config(credentials, 1), fake.config)
        assertEquals(listOf("stop", "write:2", "start", "stop", "write:1", "start"), fake.actions)
        assertTrue(fake.enabled)
    }

    @Test fun externalEditIsNotOverwrittenByFailureRecovery() {
        val fake = Fake()
        fake.externalEditOnStart = true
        fake.actualFrequency = 2412
        val failure = assertThrows(WirelessStartupException::class.java) { prepare(fake) }
        assertEquals(9, fake.config.clients)
        assertEquals(listOf("write:2", "start"), fake.actions)
        assertEquals(1, failure.suppressed.size)
    }

    @Test fun rejectedBandSwitchRestartsTheOriginallyRunningHotspot() {
        for (mutated in listOf(false, true)) {
            val fake = Fake(enabled = true)
            fake.actualFrequency = 2412
            fake.rejectFiveGhz = true
            fake.mutateRejectedWrite = mutated
            assertThrows(IllegalStateException::class.java) { prepare(fake) }
            assertEquals(Config(credentials, 1), fake.config)
            assertTrue(fake.enabled)
            assertEquals(if (mutated) listOf("stop", "write:2", "stop", "write:1", "start")
                else listOf("stop", "write:2", "stop", "start"), fake.actions)
        }
    }

    @Test fun passwordsAreRedactedAndUnsupportedCredentialsAreRejected() {
        assertFalse(credentials.toString().contains(credentials.passphrase))
        assertThrows(IllegalArgumentException::class.java) {
            MzdHotspotCredentials("Car network", "short", ManualHotspotSecurity.WPA2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MzdHotspotCredentials("Car network", "", ManualHotspotSecurity.WPA2)
        }
    }

    @Test fun onlyAnObservedFiveGhzApCanBeReady() {
        assertFalse(MzdHotspotRadio(true).fiveGhzReady())
        assertFalse(MzdHotspotRadio(true, listOf(2412, 5180)).fiveGhzReady())
        assertFalse(MzdHotspotRadio(false, listOf(5180)).fiveGhzReady())
        assertFalse(MzdHotspotRadio(true, listOf(5955)).fiveGhzReady())
        assertTrue(MzdHotspotRadio(true, listOf(5180)).fiveGhzReady())
    }
}
