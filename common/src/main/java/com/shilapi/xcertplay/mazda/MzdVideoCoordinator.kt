package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlaySessionDisplay
import com.shilapi.xcertplay.CarPlayViewAreas
import com.shilapi.xcertplay.media.AndroidMediaSink

/** Owns Mazda's transition and return-picture policy; the host supplies its current video state. */
internal class MzdVideoCoordinator(
    private val activity: Activity,
    private val handler: Handler,
    private val state: () -> State?,
    private val retryLayout: (Int, Int) -> Unit,
    private val requestArea: (Int) -> Boolean = { false },
) {
    class State(val output: View, val surface: Surface?, val display: CarPlaySessionDisplay?,
        val sink: AndroidMediaSink, val parkedPicture: ImageView?) {
        val panelWidth: Int get() = (output.parent as? MzdVideoCanvas)
            ?.takeIf { it.isFixedCanvas }?.width ?: output.width
    }

    private var mask: MzdTransitionMask? = null
    private var movingTo: Int? = null
    private var generation = 0
    val isTransitionShowing: Boolean get() = mask?.isShowing == true
    val isPanelMoving: Boolean get() = movingTo != null

    fun begin(targetWidth: Int, deferUntilPanelEnd: Boolean = false): Boolean {
        if (!MzdIntegration.cockpit(activity)) return false
        val current = state() ?: return false
        if (current.display == null) return false
        val parent = current.output.parent as? FrameLayout ?: return false
        val transition = mask?.takeIf { it.view.parent === parent } ?: run {
            mask?.dispose()
            val covered = current.parkedPicture?.takeIf { it.parent === parent } ?: current.output
            MzdTransitionMask(parent, covered, handler, checkFrame = ::checkFrame,
                requestPicture = { state()?.sink?.refreshPicture(SCREEN_TYPE_MAIN) },
                renderedFrames = { state()?.sink?.liveVideoCounters()?.rendered ?: -1L },
                textureOutput = current.output is TextureView,
                retryLayout = { state()?.let { retryLayout(it.panelWidth, it.output.height) } })
                .also { mask = it }
        }
        movingTo = if (deferUntilPanelEnd) targetWidth else null
        val token = ++generation
        val oldArea = current.display.viewAreas?.let { it.layoutArea(it.current) }
        transition.begin(targetWidth)
        if (!transition.hasPicture && oldArea != null) {
            copyCanvas(current) { frame ->
                val picture = if (generation == token && mask === transition && transition.isShowing)
                    MzdTransitionPicture.blur(frame, current.display.width, current.display.height, oldArea) else null
                picture?.let(transition::showPicture)
            }
        }
        // The target is known before the first animated resize. Negotiate it under the mask,
        // so the iPhone and the OEM panel can animate together instead of pausing at the end.
        if (MzdVideoLayout.isSettledPanel(targetWidth, current.output.height)) {
            current.display.viewAreas?.let { areas ->
                val kind = if (MzdViewAreas.isNarrow(targetWidth, current.output.height))
                    CarPlayViewAreas.Kind.SPLIT_SCREEN else CarPlayViewAreas.Kind.FULL_SCREEN
                val target = areas.index(kind, areas.areas[areas.current].dockEdge)
                if (target != null && (target == areas.current || requestArea(target))) {
                    areas.use(target)
                    transition.areaRequested(targetWidth)
                }
            }
        }
        return true
    }

    fun areaMatches(width: Int, height: Int): Boolean {
        if (!isTransitionShowing || !MzdIntegration.cockpit(activity) ||
            !MzdVideoLayout.isSettledPanel(width, height)) return true
        val areas = state()?.display?.viewAreas ?: return false
        val kind = if (MzdViewAreas.isNarrow(width, height)) CarPlayViewAreas.Kind.SPLIT_SCREEN
            else CarPlayViewAreas.Kind.FULL_SCREEN
        return areas.kindOf(areas.current) == kind
    }

    fun settle(width: Int, height: Int) {
        if (!isPanelMoving && MzdVideoLayout.isSettledPanel(width, height) && areaMatches(width, height))
            mask?.settle(width)
    }

    fun endPanelTransition(width: Int): Boolean {
        if (movingTo != width) return false
        movingTo = null
        return true
    }

    fun textureUpdated() { mask?.textureUpdated() }
    fun dispose() { ++generation; mask?.dispose(); mask = null; movingTo = null }

    private fun copyCanvas(current: State, copied: (Bitmap) -> Unit) {
        val source = current.surface?.takeIf { it.isValid } ?: return
        val display = current.display ?: return
        val width = minOf(display.width, MzdTransitionFrame.CAPTURE_WIDTH).coerceAtLeast(1)
        val height = (width * display.height / display.width.coerceAtLeast(1)).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (runCatching {
            PixelCopy.request(source, bitmap, { result ->
                try {
                    val latest = state()
                    if (result == PixelCopy.SUCCESS && latest?.surface === source &&
                        latest.output === current.output && latest.display === display) copied(bitmap)
                } finally { bitmap.recycle() }
            }, handler)
        }.isFailure) bitmap.recycle()
    }

    private fun checkFrame(checked: (Boolean) -> Unit) {
        val current = state()
        val output = current?.output
        val display = current?.display
        val areas = display?.viewAreas
        val panelWidth = current?.panelWidth ?: 0
        val source = current?.surface?.takeIf { it.isValid }
        if (output == null || display == null || areas == null || source == null ||
            !MzdVideoLayout.isSettledPanel(panelWidth, output.height) ||
            !areaMatches(panelWidth, output.height)) {
            checked(false)
            return
        }
        val index = areas.current
        val area = areas.layoutArea(index)
        val width = minOf(display.width, MzdTransitionFrame.CAPTURE_WIDTH).coerceAtLeast(1)
        val height = (width * display.height / display.width.coerceAtLeast(1)).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Read the raw decoder canvas, before the TextureView crop can conceal stale padding.
        if (runCatching {
            PixelCopy.request(source, bitmap, { result ->
                val latest = state()
                val ready = result == PixelCopy.SUCCESS && latest != null && latest.surface === source &&
                    latest.output === output && latest.display?.viewAreas === areas && areas.current == index &&
                    runCatching { MzdTransitionFrame.matchesArea(bitmap, display.width, display.height, area) }.getOrDefault(false)
                bitmap.recycle()
                checked(ready)
            }, handler)
        }.isFailure) { bitmap.recycle(); checked(false) }
    }

    fun keepReturnPicture(lastPicture: Bitmap?, parked: Boolean, captureFallback: () -> Bitmap?) {
        val current = state() ?: return
        if (!MzdIntegration.enabled(activity) || !CarPlayBackgroundSession.active ||
            !CarPlayBackgroundSession.isOwner(activity)) return
        val texture = current.output as? TextureView
        val picture = if (parked) lastPicture else if (texture != null && texture.isAvailable &&
            texture.width > 0 && texture.height > 0) {
            runCatching { texture.getBitmap((texture.width / 2).coerceAtLeast(1),
                (texture.height / 2).coerceAtLeast(1)) }.getOrNull()
        } else captureFallback()
        picture?.let { CarPlayBackgroundSession.keepReturnPicture(activity, current.sink, it) }
    }

    private companion object { const val SCREEN_TYPE_MAIN = 110 }
}
