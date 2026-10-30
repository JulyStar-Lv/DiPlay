package com.shilapi.display.android

import android.view.Surface

/** Pixel transport is separate from the immutable navigation snapshot and its work queue. */
/** Content requested from the source; a phone card does not guarantee a junction image. */
enum class MapStreamContent { MAP, INSTRUCTION_CARD, MAP_WITH_INSTRUCTION_CARD }

data class MapStreamRequest(
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
    val content: MapStreamContent = MapStreamContent.MAP,
) {
    init {
        require(width > 0 && height > 0 && framesPerSecond in 1..60)
        require(minOf(insetLeft, insetTop, insetRight, insetBottom) >= 0)
        require(insetLeft + insetRight < width && insetTop + insetBottom < height)
    }
}

interface MapSurfaceConsumer {
    fun attach(surface: Surface)
    /** Call released only after all decoders have stopped using this surface. Never block the UI. */
    fun detach(surface: Surface, released: () -> Unit)
}

interface VehicleMapLease : AutoCloseable {
    fun streamActive(active: Boolean)
    fun videoSize(width: Int, height: Int)
    override fun close()
}

interface VehicleMapOutput {
    val request: MapStreamRequest
    /** A new lease replaces the previous consumer; a late close must not clear its successor. */
    fun attach(consumer: MapSurfaceConsumer): VehicleMapLease
}
