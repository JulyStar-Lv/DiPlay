package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.android.MapStreamRequest
import com.shilapi.display.android.MapStreamContent
import com.shilapi.xcertplay.airplay.AirPlayInsets
import org.junit.Assert.*
import org.junit.Test

class CarPlayAuxiliaryMapTest {
    @Test fun combinedContentKeepsTheMapAndRequestsThePhoneComposedCard() {
        val config = CarPlayAuxiliaryMap.config(MapStreamRequest(256, 256, 15,
            content = MapStreamContent.MAP_WITH_INSTRUCTION_CARD))
        assertEquals("maps:/car/instrumentcluster", config.initialUrl)
        assertEquals(AirPlayInsets(), config.safeArea)
        assertEquals(0, config.features)
        assertEquals(0, config.primaryInputDevice)
    }
    @Test fun instructionRequestSelectsPhoneCardInsteadOfMap() {
        val config = CarPlayAuxiliaryMap.config(MapStreamRequest(256, 256, 15,
            content = MapStreamContent.INSTRUCTION_CARD))
        assertEquals("maps:/car/instrumentcluster/instructioncard", config.initialUrl)
        assertEquals(AirPlayInsets(), config.safeArea)
        assertEquals(256, config.widthPixels)
        assertEquals(0, config.primaryInputDevice)
    }
    @Test fun outputContractDeterminesMapOfferWithoutBydGeometry() {
        val config = CarPlayAuxiliaryMap.config(MapStreamRequest(352, 352, 15, 28, 24, 28, 36))
        assertEquals(352, config.widthPixels)
        assertEquals(352, config.heightPixels)
        assertEquals(15, config.fps)
        assertEquals(AirPlayInsets(24, 36, 28, 28), config.safeArea)
        assertEquals("maps:/car/instrumentcluster/map", config.initialUrl)
        assertEquals(0, config.primaryInputDevice)
    }
}
