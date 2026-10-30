package com.shilapi.xcertplay.mazda

import android.os.Bundle
import com.shilapi.vehicle.mzd.MzdMediaSource
import com.shilapi.display.android.MapStreamContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MzdHudSourceConfigTest {
    @Test fun mapContentIsExplicitAndUnknownValuesCannotSilentlyShowAMap() {
        assertEquals(MapStreamContent.MAP, mzdHudMapContent(null))
        assertEquals(MapStreamContent.INSTRUCTION_CARD, mzdHudMapContent(Bundle().apply {
            putString("com.shilapi.vehicle.mzd.HUD_MAP_CONTENT", "instruction_card")
        }))
        assertEquals(MapStreamContent.MAP_WITH_INSTRUCTION_CARD, mzdHudMapContent(Bundle().apply {
            putString("com.shilapi.vehicle.mzd.HUD_MAP_CONTENT", "map_with_instruction_card")
        }))
        assertThrows(IllegalArgumentException::class.java) { mzdHudMapContent(Bundle().apply {
            putString("com.shilapi.vehicle.mzd.HUD_MAP_CONTENT", "bad")
        }) }
    }
    private fun metadata(sourceType: Int) = Bundle().apply {
        putInt("com.shilapi.vehicle.mzd.HUD_MEDIA_SOURCE_TYPE", sourceType)
    }

    @Test fun absentMetadataKeepsVerifiedDefault27() {
        assertEquals(MzdMediaSource.TEXT_COMPAT_27, mzdHudMediaSource(null))
        assertEquals(MzdMediaSource.TEXT_COMPAT_27, mzdHudMediaSource(Bundle()))
    }

    @Test fun oemAssemblyAcceptsBothIconFreeTextSources() {
        assertEquals(MzdMediaSource.BLUETOOTH_APPEARANCE_20, mzdHudMediaSource(metadata(20)))
        assertEquals(MzdMediaSource.TEXT_COMPAT_27, mzdHudMediaSource(metadata(27)))
        assertEquals(MzdMediaSource.TEXT_COMPAT_29, mzdHudMediaSource(metadata(29)))
    }

    @Test fun nativeCarPlayAndIconSourcesCannotBeAccidentallySelectedAsTextCompatibility() {
        for (sourceType in listOf(0, 16, 18, 28, 30, 35, 255)) {
            assertThrows(IllegalArgumentException::class.java) { mzdHudMediaSource(metadata(sourceType)) }
        }
    }
}
