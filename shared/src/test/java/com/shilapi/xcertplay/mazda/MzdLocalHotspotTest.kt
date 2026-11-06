package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.orchestration.FirstTcpWatchdog
import org.junit.Assert.*
import org.junit.Test

class MzdLocalHotspotTest {
    @Test fun iphoneArrivingAt32SecondsCancelsTheMazdaTimeoutBeforeAnyCleanup() {
        var now = 0L
        var scheduled: (() -> Unit)? = null
        var cancelled = false
        var failures = 0
        val identity = AirPlayListenerIdentity(1)
        val watch = FirstTcpWatchdog(identity, { delay, action ->
            assertEquals(45_000L, delay)
            scheduled = action
            val cancel: () -> Unit = { cancelled = true }
            cancel
        }, { failures++ }, { now * 1_000_000 }, timeoutMillis = MzdLocalHotspot.FIRST_TCP_TIMEOUT_MILLIS)
        watch.startSessionSent(0)
        now = 32_000
        assertTrue(watch.accepted(AirPlayTcpAccepted(identity, false, now * 1_000_000)))
        assertTrue(watch.sessionEstablished())
        assertTrue(cancelled)
        now = 45_000
        scheduled!!()
        assertEquals(0, failures)
        assertFalse(watch.terminated)
    }

    @Test fun mazdaTimeoutRemainsBoundedAndCannotReviveAfterCleanupStarts() {
        var scheduled: (() -> Unit)? = null
        var failures = 0
        val identity = AirPlayListenerIdentity(1)
        val watch = FirstTcpWatchdog(identity, { _, action -> scheduled = action; {} }, { failures++ },
            timeoutMillis = MzdLocalHotspot.FIRST_TCP_TIMEOUT_MILLIS)
        watch.startSessionSent(0)
        scheduled!!()
        assertEquals(1, failures)
        assertFalse(watch.accepted(AirPlayTcpAccepted(identity, false, 46_000_000_000)))
        assertFalse(watch.sessionEstablished())
    }

    @Test fun credentialsAreStableForOneInstallationAndChangeForAnother() {
        val first = MzdLocalHotspot.deriveCredentials("DiPlay", 1793894400123)
        assertEquals(first, MzdLocalHotspot.deriveCredentials("DiPlay", 1793894400123))
        assertNotEquals(first.password, MzdLocalHotspot.deriveCredentials("DiPlay", 1793894400124).password)
        assertTrue(first.password.matches(Regex("[0-9]{12}")))
        assertEquals("DiPlay-5G-${first.password.take(4)}", first.ssid)
    }

    @Test fun localizedApplicationNameFitsTheSsidByteLimitAndKeepsItsSuffix() {
        val credentials = MzdLocalHotspot.deriveCredentials("车机无线连接应用".repeat(5), 1793894400123)
        assertTrue(credentials.ssid.toByteArray(Charsets.UTF_8).size <= 32)
        assertTrue(credentials.ssid.endsWith("-5G-${credentials.password.take(4)}"))
    }
}
