package com.shilapi.xcertplay.projection.carplay

import com.shilapi.display.android.MapStreamRequest
import com.shilapi.display.android.MapStreamContent
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** Protocol-specific content URL stays on the source side of the pixel contract. */
object CarPlayAuxiliaryMap {
    fun config(request: MapStreamRequest) = AirPlayDisplayConfig(
        widthPixels = request.width,
        heightPixels = request.height,
        fps = request.framesPerSecond,
        primaryInputDevice = 0,
        features = 0,
        initialUrl = when (request.content) {
            MapStreamContent.MAP -> CarPlayClusterDisplay.MAP_URL
            MapStreamContent.INSTRUCTION_CARD -> CarPlayClusterDisplay.Content.TURN_CARD.url
            MapStreamContent.MAP_WITH_INSTRUCTION_CARD -> CarPlayClusterDisplay.Content.INSTRUMENTS.url
        },
        safeArea = AirPlayInsets(request.insetTop, request.insetBottom, request.insetLeft, request.insetRight),
        safeAreaDrawOutside = true,
    )
}
