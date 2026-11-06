package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class VehicleRouteSharingTest {
    private fun inner(bytes: ByteArray): Map<*, *> =
        OpackCodec.decode((OpackCodec.decode(bytes) as Map<*, *>)["pluginData"] as ByteArray) as Map<*, *>

    @Test fun routeSharingRequiresAnExplicitOfferAndPhoneProposal() {
        val config = AirPlayConfig("test", "id", "bt", "1", AirPlayDisplayConfig(800, 480))
        assertFalse(AirPlayInfoPlist.build(config).containsKey("vehicleStateProtocolInfo"))
        assertFalse(setupEnabledFeatures(config, listOf(VehicleRouteSharing.FEATURE)).contains(VehicleRouteSharing.FEATURE))
        val enabled = config.copy(routeSharing = true)
        assertTrue(AirPlayInfoPlist.build(enabled).containsKey("vehicleStateProtocolInfo"))
        assertFalse(setupEnabledFeatures(enabled, null).contains(VehicleRouteSharing.FEATURE))
        assertTrue(setupEnabledFeatures(enabled, listOf(VehicleRouteSharing.FEATURE)).contains(VehicleRouteSharing.FEATURE))
        val decoded = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(enabled))) as Map<*, *>
        assertNotNull(decoded["vehicleStateProtocolInfo"])
    }

    @Test fun configurationContainsOnlyNavigationAndRegisterIncludesVehicleConsentReason() {
        val state = VehicleRouteSharing(null) {}
        val config = inner(state.receive(VehicleRouteSharing.wrap(mapOf("command" to "configRequest", "transactionID" to 7L))).single())
        assertEquals("configResponse", config["command"])
        assertEquals(7L, config["transactionID"])
        val accessories = (config["values"] as Map<*, *>)["accessories"] as List<*>
        assertEquals(1, accessories.size)
        assertEquals(256L, (accessories.single() as Map<*, *>)["iid"])
        val register = inner(state.receive(VehicleRouteSharing.wrap(mapOf("command" to "registerRequest", "transactionID" to 8L,
            "values" to listOf(263L, 264L, 260L, 999L)))).single())
        assertEquals(true, (register["values"] as Map<*, *>)[264L])
        assertEquals(-6727L, (register["errors"] as Map<*, *>)[260L])
        assertEquals(-6727L, (register["errors"] as Map<*, *>)[999L])
    }

    @Test fun writesKeepLegsOpaqueAndRejectVehicleOwnedOrMalformedCharacteristics() {
        val messages = mutableListOf<String>()
        val state = VehicleRouteSharing(null, messages::add)
        val legs = listOf(mapOf("unverified" to byteArrayOf(1, 2, 3)))
        val reply = inner(state.receive(VehicleRouteSharing.wrap(mapOf("command" to "writeRequest", "transactionID" to 4L,
            "values" to mapOf(260L to legs, 264L to false, 267L to 9L, 259L to "invalid")))).single())
        val errors = reply["errors"] as Map<*, *>
        assertEquals(0L, errors[260L])
        assertEquals(-6727L, errors[264L])
        assertEquals(-6727L, errors[267L])
        assertEquals(-6727L, errors[259L])
        assertTrue(messages.any { it.contains("legs=1") })
        assertFalse(messages.any { it.contains("unverified") })
    }

    @Test fun opackDecodesIndependentReferenceFixture() {
        val hex = "e4466572726f7273eb31040131b9e5310d01a2310501a2310e01a2310a01a2310601a2310201a2" +
            "310f01a2310b01a2310301a2310c01a24d7472616e73616374696f6e494433a0bc52cb03e5cd02" +
            "47636f6d6d616e64507265676973746572526573706f6e73654676616c756573e23108010131110108"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val decoded = OpackCodec.decode(bytes) as Map<*, *>
        assertEquals("registerResponse", decoded["command"])
        assertEquals(11, (decoded["errors"] as Map<*, *>).size)
        assertTrue((decoded["errors"] as Map<*, *>).values.all { it == -6727L })
        assertEquals(mapOf(264L to true, 273L to 0L), decoded["values"])
    }

    @Test fun opackRejectsMalformedLengthsReferencesDuplicateKeysAndDeepNesting() {
        val invalid = listOf(byteArrayOf(0xa0.toByte()), byteArrayOf(0x64, -1, -1, -1, 127),
            byteArrayOf(0xe1.toByte(), 0x41, 107), byteArrayOf(0xdf.toByte()),
            ByteArray(40) { 0xd1.toByte() } + byteArrayOf(4), byteArrayOf(1, 2),
            byteArrayOf(0xe2.toByte(), 8, 1, 8, 2))
        invalid.forEach { bytes -> assertThrows(Exception::class.java) { OpackCodec.decode(bytes) } }
    }
}
