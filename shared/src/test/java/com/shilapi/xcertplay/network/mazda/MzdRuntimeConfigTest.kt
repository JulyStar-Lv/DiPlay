package com.shilapi.xcertplay.network.mazda

import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test

class MzdRuntimeConfigTest {
    @Test fun originalRuntimeConfigurationRequiresRealHotspotCredentialsBeforeCarPlayStarts() {
        fun config(credentials: MzdHotspotCredentials? = null) = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = WirelessHotspotMode.MANUAL,
            manualHotspotSsid = credentials?.ssid,
            manualHotspotPassphrase = credentials?.passphrase,
            manualHotspotSecurity = credentials?.security ?: ManualHotspotSecurity.WPA2,
            manualHotspotBand = ManualHotspotBand.GHZ_5,
        )
        assertThrows(IllegalArgumentException::class.java) { config() }
        val credentials = MzdHotspotCredentials("Car network", "car-password", ManualHotspotSecurity.WPA2)
        val ready = config(credentials)
        assertEquals(credentials.ssid, ready.manualHotspotSsid)
        assertEquals(credentials.passphrase, ready.manualHotspotPassphrase)
        assertEquals(credentials.security, ready.manualHotspotSecurity)
    }
}
