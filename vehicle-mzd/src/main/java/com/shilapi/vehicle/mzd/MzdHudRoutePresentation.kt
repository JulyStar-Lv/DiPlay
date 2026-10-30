package com.shilapi.vehicle.mzd

import com.shilapi.display.android.RouteMapCamera
import com.shilapi.display.android.RouteMapPresentation
import com.shilapi.display.android.RouteMapStyle
import com.shilapi.display.api.RouteScene

/** Measured ordinary J90K profile, modelled on the native HUD map configuration.
 * This is a renderer policy, not an interface to the navigation app's private map engine. */
object MzdHudRoutePresentation : RouteMapPresentation {
    override val style = RouteMapStyle(routeColor = 0xff00ffa4.toInt())
    override val renderWidth = MzdHudMapProfile.SIZE
    override val renderHeight = MzdHudMapProfile.SIZE
    override val framesPerSecond = 10

    fun zoomLevel(distanceMeters: Long?): Int? = when {
        distanceMeters == null || distanceMeters < 0 -> null
        distanceMeters <= 300 -> 19
        distanceMeters <= 600 -> 18
        else -> 17
    }

    override fun camera(scene: RouteScene): RouteMapCamera {
        // For this local producer, the source window is the far (level 17) reference span.
        // One level doubles scale. Absolute SDK zoom needs engine calibration and is not inferred.
        val divisor = when (zoomLevel(scene.distanceToManeuverMeters)) { 19 -> 4; 18 -> 2; else -> 1 }
        return RouteMapCamera((scene.viewSpanMeters / divisor).coerceAtLeast(20.0), 0.8)
    }
}
