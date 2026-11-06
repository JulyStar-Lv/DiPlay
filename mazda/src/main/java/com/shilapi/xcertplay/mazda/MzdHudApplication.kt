package com.shilapi.xcertplay.mazda

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.shilapi.display.api.VehicleDisplayFactory
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
            metadata?.getBoolean("com.shilapi.vehicle.mzd.HUD_AUTOMATIC_A2DP_HANDOVER", false) == true
        ) { Log.i("DiPlay-MzdHUD", it) }
        else VehicleDisplayFactory.NONE
    }
}

/** OEM assembly only; no vendor sourceType enters the shared projection snapshot. */
internal fun mzdHudMediaSource(metadata: Bundle?): MzdMediaSource = MzdMediaSource.fromSourceType(
    metadata?.getInt("com.shilapi.vehicle.mzd.HUD_MEDIA_SOURCE_TYPE", 27) ?: 27)
