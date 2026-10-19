package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/** Covers the animated OEM panel with the current view area, preserving video and touch geometry. */
internal object MzdVideoLayout {
    private const val FULL_RIGHT_EDGE_GUARD = 2f
    // The 2026-10-17 vehicle capture places both parent and TextureView at x=832..2560,
    // but the narrow picture's straight edge stops near x=2553. Inverting its 1728/1726
    // scale puts the coloured band in the last ~10 source pixels. Exclude it plus a filter
    // margin, in negotiated stream pixels, rather than shifting the physical OEM panel.
    private const val NARROW_RIGHT_EDGE_GUARD_AT_1080 = 12f
    // The 2026-10-19 recording exposes 2–3 pixels at 0.375 scale on the narrow
    // left edge. Exclude those eight stream pixels plus the filtering neighbour.
    private const val NARROW_LEFT_EDGE_GUARD_AT_1080 = 10f

    fun leftEdgeGuard(canvasWidth: Int, area: AirPlayViewArea): Float =
        if (area.width < canvasWidth) NARROW_LEFT_EDGE_GUARD_AT_1080 * area.height / 1080f else 0f

    fun rightEdgeGuard(canvasWidth: Int, area: AirPlayViewArea): Float =
        if (area.width < canvasWidth) {
            maxOf(FULL_RIGHT_EDGE_GUARD, NARROW_RIGHT_EDGE_GUARD_AT_1080 * area.height / 1080f)
        } else FULL_RIGHT_EDGE_GUARD
    fun isPanel(width: Int, height: Int) = height == 1080 && width in 1728..2496

    fun isSettledPanel(width: Int, height: Int) = isPanel(width, height) &&
        (width == 1728 || width == 2496)

    /** Canvas coordinates stay anchored at the same physical point behind the right-hand crop. */
    fun fixedCanvas(canvasWidth: Int, canvasHeight: Int, area: AirPlayViewArea,
        viewportWidth: Int): CarPlayVideoLayout {
        // Stream sizes are rounded to even pixels. Anchor to the exact OEM crop, not the
        // rounded area's fraction of the canvas, which can otherwise expose a black sliver.
        val physicalAreaWidth = if (area.originX > 0 && area.width < canvasWidth) 1728f
            else MzdVideoCanvas.WIDTH.toFloat()
        val areaLeft = MzdVideoCanvas.WIDTH - physicalAreaWidth
        val leftGuard = leftEdgeGuard(canvasWidth, area)
        val scale = maxOf(physicalAreaWidth /
            (area.width - leftGuard - rightEdgeGuard(canvasWidth, area)).coerceAtLeast(1f),
            MzdVideoCanvas.HEIGHT.toFloat() / canvasHeight)
        return CarPlayVideoLayout(viewportWidth - MzdVideoCanvas.WIDTH + areaLeft - (area.originX + leftGuard) * scale,
            (MzdVideoCanvas.HEIGHT - canvasHeight * scale) / 2f,
            canvasWidth * scale, canvasHeight * scale)
    }

    fun cover(canvasWidth: Int, canvasHeight: Int, area: AirPlayViewArea,
        viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val leftGuard = leftEdgeGuard(canvasWidth, area)
        val scale = maxOf(viewWidth.toFloat() /
            (area.width - leftGuard - rightEdgeGuard(canvasWidth, area)).coerceAtLeast(1f),
            viewHeight.toFloat() / area.height)
        // Keep the driver's dock at the left edge while the sidebar moves. Any vertical overflow
        // is centred; unused stream pixels beyond the active area remain outside the viewport.
        return CarPlayVideoLayout(-(area.originX + leftGuard) * scale,
            (viewHeight - area.height * scale) / 2f - area.originY * scale,
            canvasWidth * scale, canvasHeight * scale)
    }
}
