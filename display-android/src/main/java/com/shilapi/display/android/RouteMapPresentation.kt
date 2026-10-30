package com.shilapi.display.android

import android.graphics.Color
import com.shilapi.display.api.RouteScene

data class RouteMapStyle(
    val referenceSize: Float = 178f,
    val roadColor: Int = Color.rgb(87, 96, 104),
    val routeColor: Int = Color.rgb(0, 222, 255),
    val roadWidth: Float = 4f,
    val routeWidth: Float = 10f,
    val routeOutlineWidth: Float = 14f,
) {
    init {
        require(referenceSize.isFinite() && referenceSize in 32f..2048f)
        require(roadWidth.isFinite() && roadWidth in 1f..32f)
        require(routeWidth.isFinite() && routeWidth in 1f..64f)
        require(routeOutlineWidth.isFinite() && routeOutlineWidth in routeWidth..128f)
    }
}

data class RouteMapCamera(val viewSpanMeters: Double, val markerYFraction: Double = 0.74)

/** OEM assembly supplies its presentation policy; sources provide only navigation data. */
interface RouteMapPresentation {
    val style: RouteMapStyle get() = RouteMapStyle()
    val renderWidth: Int? get() = null
    val renderHeight: Int? get() = null
    val framesPerSecond: Int get() = 5
    fun camera(scene: RouteScene) = RouteMapCamera(scene.viewSpanMeters)

    companion object { val DEFAULT = object : RouteMapPresentation {} }
}
