package com.shilapi.xcertplay

import com.shilapi.display.api.VehicleDisplayFactory

/** Implemented by the selected application's Application; common never imports an OEM driver. */
interface VehicleDisplayProvider {
    val vehicleDisplayFactory: VehicleDisplayFactory
    val vehicleMapOutput: com.shilapi.display.android.VehicleMapOutput? get() = null
}
