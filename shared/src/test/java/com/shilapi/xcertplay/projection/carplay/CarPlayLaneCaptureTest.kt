package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw
import org.junit.Assert.*
import org.junit.Test

class CarPlayLaneCaptureTest {
    @Test fun repeatedAnglesAreRetainedAndPrivateDescriptionsAreOmitted() {
        val frame = buildRaw(0x5204) {
            u16(0, 42); u16(1, 3); string(3, "Private road")
            group(2) { u16(0, 0); u8(1, 0); i16(2, -90) }
            group(2) { u16(0, 1); u8(1, 2); i16(2, 0); i16(2, 90); i16(3, 90) }
        }
        val event = CarPlayLaneCapture.diagnostic(frame)
        assertEquals("stage=lanes frame=5204 valid=true index=3 count=2 layout=0/0/-90/null;1/2/0+90/90", event)
        assertFalse(event.contains("Private"))
    }

    @Test fun missingOrMalformedAnglesNeverBecomeFabricatedStraightLanes() {
        for (frame in listOf(
            buildRaw(0x5204) { u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2) } },
            buildRaw(0x5204) { u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); u8(2, 0) } },
            buildRaw(0x5204) { u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 181) } },
        )) assertEquals("stage=lanes frame=5204 valid=false index=null count=0 layout=none",
            CarPlayLaneCapture.diagnostic(frame))
    }
}
