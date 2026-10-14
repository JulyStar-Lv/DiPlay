package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/** Covers the animated OEM panel with the current view area, preserving video and touch geometry. */
internal object MzdVideoLayout {
    // The encoder/compositor can leave the last active column black. Keep that column and its
    // bilinear neighbour outside the viewport, including half-resolution streams.
    const val RIGHT_EDGE_GUARD = 2
    fun isPanel(width: Int, height: Int) = height == 1080 && width in 1728..2496

    fun isSettledPanel(width: Int, height: Int) = isPanel(width, height) &&
        (width == 1728 || width == 2496)

    fun cover(canvasWidth: Int, canvasHeight: Int, area: AirPlayViewArea,
        viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val scale = maxOf(viewWidth.toFloat() / (area.width - RIGHT_EDGE_GUARD).coerceAtLeast(1),
            viewHeight.toFloat() / area.height)
        // Keep the driver's dock at the left edge while the sidebar moves. Any vertical overflow
        // is centred; unused stream pixels beyond the active area remain outside the viewport.
        return CarPlayVideoLayout(-area.originX * scale,
            (viewHeight - area.height * scale) / 2f - area.originY * scale,
            canvasWidth * scale, canvasHeight * scale)
    }
}
