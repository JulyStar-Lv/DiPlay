package com.shilapi.display.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.shilapi.display.api.*

/** Source event identity, independent of the route-frame revision and all OEM Binder numbers. */
data class JunctionImageEvent(
    val session: SourceSessionKey,
    val sourceRouteId: String,
    val junctionId: String,
    val revision: Long,
    val receivedAtMillis: Long,
    val origin: RouteSceneOrigin,
) {
    init {
        require(sourceRouteId.isNotBlank() && sourceRouteId.length <= 128)
        require(junctionId.isNotBlank() && junctionId.length <= 128)
        require(revision >= 0 && receivedAtMillis >= 0)
    }
}

/** Own immutable decoded bitmaps. Native engine vector/3D buffers are not raster image files. */
class RasterJunctionImage private constructor(internal val road: Bitmap, internal val arrow: Bitmap?) {
    val width get() = road.width
    val height get() = road.height

    companion object {
        private const val MAX_ENCODED_BYTES = 4 * 1024 * 1024
        private const val MAX_PIXELS = 2 * 1024 * 1024

        /** Decode on a source worker, not a protocol listener or the main thread. */
        fun decode(roadBytes: ByteArray, arrowBytes: ByteArray? = null): RasterJunctionImage? = try {
            require(roadBytes.isNotEmpty() && roadBytes.size.toLong() + (arrowBytes?.size ?: 0) <= MAX_ENCODED_BYTES)
            // A protocol worker may reuse its buffers after dispatch. Bounds and decode must
            // inspect the same immutable snapshot, including for the allocation limits below.
            val roadInput = roadBytes.copyOf()
            val arrowInput = arrowBytes?.copyOf()
            fun bounds(bytes: ByteArray): Pair<Int, Int> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                require(options.outWidth in 1..2048 && options.outHeight in 1..2048)
                return options.outWidth to options.outHeight
            }
            val size = bounds(roadInput)
            if (arrowInput != null) require(bounds(arrowInput) == size)
            require(size.first.toLong() * size.second * (if (arrowInput == null) 1 else 2) <= MAX_PIXELS)
            fun decodeBitmap(bytes: ByteArray): Bitmap {
                val options = BitmapFactory.Options().apply { inMutable = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
            }
            RasterJunctionImage(decodeBitmap(roadInput), arrowInput?.let(::decodeBitmap))
        } catch (_: RuntimeException) { null }
    }
}

data class HudMapFrame(val map: RouteScene?, val junction: RasterJunctionImage?, val junctionEvent: JunctionImageEvent?)

/** Separate base-map and junction-layer lifecycles. Hiding a junction restores the latest map,
 * never a cached frame from before the junction. A show event requires an active matching route. */
class HudMapLayers(
    private val session: SourceSessionKey,
    private val clock: () -> Long,
    private val allowSampleReplay: Boolean = false,
    private val maximumJunctionAgeMillis: Long = 15_000,
) : AutoCloseable {
    private val maps = RouteSceneStore(session, clock, allowSampleReplay)
    private var routeId: String? = null
    private var eventRevision = -1L
    private var event: JunctionImageEvent? = null
    private var image: RasterJunctionImage? = null
    private var closed = false
    init { require(maximumJunctionAgeMillis in 1..90_000) }

    @Synchronized fun submit(scene: RouteScene): Boolean {
        val previous = maps.current()
        if (closed || !maps.submit(scene)) return false
        if (previous == null || previous.origin != scene.origin || scene.sourceRouteId != routeId ||
            scene.phase != NavigationPhase.GUIDING) clearJunction()
        routeId = scene.sourceRouteId
        return true
    }

    private fun accepts(update: JunctionImageEvent): Boolean {
        val map = maps.current() ?: return false
        return !closed && update.session == session && update.sourceRouteId == map.sourceRouteId &&
            update.revision > eventRevision && clock() - update.receivedAtMillis in 0..maximumJunctionAgeMillis &&
            (allowSampleReplay || update.origin != RouteSceneOrigin.SAMPLE_REPLAY) && update.origin == map.origin
    }

    @Synchronized fun showJunction(update: JunctionImageEvent, raster: RasterJunctionImage): Boolean {
        if (!accepts(update)) return false
        eventRevision = update.revision; event = update; image = raster
        return true
    }

    @Synchronized fun hideJunction(update: JunctionImageEvent): Boolean {
        if (!accepts(update) || (event != null && update.junctionId != event!!.junctionId)) return false
        eventRevision = update.revision
        clearJunction()
        return true
    }

    @Synchronized fun frame(): HudMapFrame {
        val map = maps.current()
        if (map == null || event?.let { clock() - it.receivedAtMillis !in 0..maximumJunctionAgeMillis } == true) clearJunction()
        return HudMapFrame(map, image, event)
    }

    private fun clearJunction() { image = null; event = null }
    @Synchronized override fun close() { closed = true; maps.close(); clearJunction() }
}

/** A full-viewport junction layer above a separately retained small map. */
class HudMapRenderer(private val presentation: RouteMapPresentation = RouteMapPresentation.DEFAULT) {
    private val map = RouteSceneRenderer(presentation)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, frame: HudMapFrame, width: Int = canvas.width, height: Int = canvas.height) {
        map.draw(canvas, frame.map, width, height)
        val raster = frame.junction?.takeIf { frame.map?.phase == NavigationPhase.GUIDING } ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(width.toFloat() / raster.width, height.toFloat() / raster.height)
        val w = raster.width * scale; val h = raster.height * scale
        val target = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(raster.road, null, target, paint)
        raster.arrow?.let { canvas.drawBitmap(it, null, target, paint) }
        if (frame.map?.origin == RouteSceneOrigin.SAMPLE_REPLAY || frame.junctionEvent?.origin == RouteSceneOrigin.SAMPLE_REPLAY) {
            val factor = minOf(width, height) / presentation.style.referenceSize
            paint.color = Color.rgb(255, 192, 64); paint.textSize = 12f * factor
            canvas.drawText("SIM", 5f * factor, 14f * factor, paint)
        }
        canvas.restoreToCount(save)
    }
}
