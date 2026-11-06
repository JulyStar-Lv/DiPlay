package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class SpatialAudioOfferTest {
    private val base = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "1.0",
        AirPlayDisplayConfig(800, 480))

    @Test fun defaultAndDisabledAudioNeverAdvertiseSpatialFormats() {
        assertFalse(base.bufferedAudioOutputEnabled)
        assertFalse((AirPlayInfoPlist.build(base)["audioFormats"] as List<*>).any {
            (it as Map<*, *>).containsKey("audioOutputFormatsExtended")
        })
        val disabled = base.copy(spatialAudioFormats = listOf(65, 68), disableAudioOutput = true)
        assertFalse(disabled.bufferedAudioOutputEnabled)
        assertFalse(AirPlayInfoPlist.build(disabled).containsKey("audioFormats"))
    }

    @Test fun spatialOptInOffersOnlyImplementedLayoutsAndStereoForItemSwitches() {
        val config = base.copy(spatialAudioFormats = listOf(65, 68, 66, 65))
        assertTrue(config.bufferedAudioOutputEnabled)
        val info = AirPlayInfoPlist.build(config)
        val buffered = (info["audioFormats"] as List<*>).map { it as Map<*, *> }.single { it["type"] == 103 }
        assertEquals(listOf(65, 68, 23), buffered["audioOutputFormatsExtended"])
        assertEquals(0x400000, buffered["audioOutputFormats"])
        assertFalse(info.containsKey("DCXEnabled"))
    }

    @Test fun dcxCapabilityIsNestedAndRequiresBothOptInAndItsSupportedLayout() {
        for ((formats, enabled, advertised) in listOf(
            Triple(listOf(68), false, false),
            Triple(listOf(68), true, true),
            Triple(listOf(65, 68), true, true),
            Triple(listOf(65), true, false),
            Triple(emptyList<Int>(), true, false),
        )) {
            val config = base.copy(mainBufferedAudio = true, spatialAudioFormats = formats, dolbyDcxEnabled = enabled)
            val info = AirPlayInfoPlist.build(config)
            assertFalse("DCXEnabled belongs inside mainBufferedInfo", info.containsKey("DCXEnabled"))
            val bufferedInfo = info["mainBufferedInfo"] as Map<*, *>
            assertEquals("formats=$formats enabled=$enabled", advertised, bufferedInfo["DCXEnabled"] == true)
            if (!advertised) assertFalse(bufferedInfo.containsKey("DCXEnabled"))
        }
        val disabled = base.copy(spatialAudioFormats = listOf(68), dolbyDcxEnabled = true, disableAudioOutput = true)
        assertFalse(AirPlayInfoPlist.build(disabled).containsKey("mainBufferedInfo"))
    }

}
