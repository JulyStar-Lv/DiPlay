package com.shilapi.xcertplay.mazda

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.shilapi.display.api.VehicleDisplayFactory
import com.shilapi.display.android.MapStreamContent
import com.shilapi.vehicle.mzd.MzdVehicleDisplayFactory
import com.shilapi.vehicle.mzd.MzdMediaSource
import com.shilapi.xcertplay.VehicleDisplayProvider

/** OEM composition lives in the Mazda app, never in common/shared. */
class MzdHudApplication : MzdApplication(), VehicleDisplayProvider {
    override fun onCreate() {
        super.onCreate()
        (vehicleDisplayFactory as? MzdVehicleDisplayFactory)?.recoverInterruptedMediaHandover()
    }
    override val vehicleDisplayFactory: VehicleDisplayFactory by lazy {
        @Suppress("DEPRECATION")
        val metadata = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
        val enabled = metadata?.getBoolean("com.shilapi.vehicle.mzd.HUD_ENABLED", false) == true
        if (enabled) MzdVehicleDisplayFactory(this, mzdHudMediaSource(metadata),
            metadata?.getBoolean("com.shilapi.vehicle.mzd.HUD_AUTOMATIC_A2DP_HANDOVER", false) == true,
            metadata?.getBoolean("com.shilapi.vehicle.mzd.HUD_MAP_ENABLED", false) == true,
            mzdHudMapContent(metadata)) { Log.i("DiPlay-MzdHUD", it) }
        else VehicleDisplayFactory.NONE
    }
    override val vehicleMapOutput get() = (vehicleDisplayFactory as? MzdVehicleDisplayFactory)?.auxiliaryMapOutput
}

/** OEM assembly only; no vendor sourceType enters the shared projection snapshot. */
internal fun mzdHudMediaSource(metadata: Bundle?): MzdMediaSource = MzdMediaSource.fromSourceType(
    metadata?.getInt("com.shilapi.vehicle.mzd.HUD_MEDIA_SOURCE_TYPE", 27) ?: 27)

internal fun mzdHudMapContent(metadata: Bundle?): MapStreamContent = when (
    metadata?.getString("com.shilapi.vehicle.mzd.HUD_MAP_CONTENT") ?: "map"
) {
    "map" -> MapStreamContent.MAP
    "instruction_card" -> MapStreamContent.INSTRUCTION_CARD
    "map_with_instruction_card" -> MapStreamContent.MAP_WITH_INSTRUCTION_CARD
    else -> throw IllegalArgumentException("Unsupported HUD map content")
}
