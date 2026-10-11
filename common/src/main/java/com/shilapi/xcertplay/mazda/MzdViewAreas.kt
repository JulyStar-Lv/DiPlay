package com.shilapi.xcertplay.mazda

import com.shilapi.xcertplay.CarPlayDock
import com.shilapi.xcertplay.CarPlayViewAreas

/** Both OEM panel shapes share the full stream; the existing video/touch mapping follows the area. */
internal object MzdViewAreas {
    fun isNarrow(width: Int, height: Int) = width == 1728 && height == 1080

    fun build(width: Int, height: Int, dock: CarPlayDock, narrow: Boolean,
        sidePanel: Boolean = false, rightHandDrive: Boolean = false): CarPlayViewAreas {
        val areas = requireNotNull(CarPlayViewAreas.build(width, height,
            listOf(CarPlayViewAreas.Screen(width, height, portrait = false)), dock,
            splitWindow = { 1728f / 2496f to 1f }, startPortrait = false,
            sidePanel = sidePanel, rightHandDrive = rightHandDrive))
        if (narrow) areas.index(CarPlayViewAreas.Kind.SPLIT_SCREEN, dock.edge)?.let(areas::use)
        return areas
    }
}
