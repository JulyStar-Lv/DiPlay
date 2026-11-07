package com.shilapi.vehicle.mzd

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdHudConfigTest {
    private class ConfigService : Binder(), IInterface {
        var value: String? = "2"
        var denied = false
        var supported = true
        val codes = mutableListOf<Int>()
        init { attachInterface(this, "com.tinnove.config.aidl.IConfigInterface") }
        override fun asBinder() = this
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            assertEquals(3, code)
            data.enforceInterface("com.tinnove.config.aidl.IConfigInterface")
            assertEquals("car_feature_hud_type", data.readString())
            assertEquals(0, data.dataAvail())
            if (!supported) return false
            if (denied) reply!!.writeException(SecurityException("denied")) else {
                reply!!.writeNoException(); reply.writeString(value)
            }
            return true
        }
    }

    @Test fun readsOnlyTheOemHudFeatureAndPreservesThreeDimensionalType() {
        val config = ConfigService()
        assertEquals(2, readMzdHudTypeFrom(config))
        config.value = "3"
        assertEquals(3, readMzdHudTypeFrom(config))
        assertEquals(listOf(3, 3), config.codes)
    }

    @Test fun missingDeniedUnsupportedOrMalformedConfigurationReturnsNoType() {
        assertNull(readMzdHudTypeFrom(null))
        assertNull(readMzdHudTypeFrom(Binder()))
        val config = ConfigService()
        for (value in listOf(null, "", "arhud", "2147483648")) {
            config.value = value; assertNull(readMzdHudTypeFrom(config))
        }
        config.denied = true; assertNull(readMzdHudTypeFrom(config))
        config.denied = false; config.supported = false; assertNull(readMzdHudTypeFrom(config))
    }
}
