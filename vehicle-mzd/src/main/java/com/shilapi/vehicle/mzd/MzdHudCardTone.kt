package com.shilapi.vehicle.mzd

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import com.shilapi.display.android.MapStreamContent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A display policy for phone-composed instruction cards, never for map/combined video. */
internal object MzdHudCardTone {
    // Preserve solid arrow pixels when finding the phone's secondary grey. A 16px
    // thumbnail can blend a thin inactive arrow into its background.
    const val PROBE_SIZE = MzdHudMapProfile.SIZE
    const val PROBE_INTERVAL_MILLIS = 500L
    // J90K laneguide/15.png and laneguide/0.png, confirmed in native HUD captures.
    // These resource colours are shared across phone day/night themes.
    const val RECOMMENDED_LEVEL = 255
    const val NON_RECOMMENDED_LEVEL = 102
    enum class Mode { LIGHT, DARK, ORIGINAL }
    data class Palette(val mode: Mode, val backgroundLevel: Int = 0, val secondaryLevel: Int? = null)
    data class Filters(val source: Paint, val target: Paint? = null)

    fun enabled(content: MapStreamContent) = content == MapStreamContent.INSTRUCTION_CARD

    /** Only neutral, flat-edged cards qualify. Unknown/coloured imagery keeps its source colours. */
    fun detect(bitmap: Bitmap): Palette? {
        if (bitmap.width < 4 || bitmap.height < 4) return null
        val edge = mutableListOf<Int>()
        for (step in 1..6) {
            val x = step * (bitmap.width - 1) / 7
            val y = step * (bitmap.height - 1) / 7
            edge += bitmap.getPixel(x, 1)
            edge += bitmap.getPixel(x, bitmap.height - 2)
            edge += bitmap.getPixel(1, y)
            edge += bitmap.getPixel(bitmap.width - 2, y)
        }
        // A failed TextureView copy leaves our pre-cleared bitmap transparent; do not expose it.
        if (edge.any { Color.alpha(it) < 240 }) return null
        val levels = edge.map(::luminance).sorted()
        val background = levels[levels.size / 2].roundToInt()
        if (edge.count { abs(luminance(it) - background) <= 16f && chroma(it) <= 48 } < 20)
            return Palette(Mode.ORIGINAL)
        var coloured = 0
        val histogram = IntArray(256)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 240) return Palette(Mode.ORIGINAL)
            if (chroma(pixel) > 48) coloured++
            histogram[luminance(pixel).roundToInt().coerceIn(0, 255)]++
        }
        if (coloured > bitmap.width * bitmap.height / 64) return Palette(Mode.ORIGINAL)
        // Quantise the background to keep codec noise from changing the filter every probe.
        val level = ((background + 4) / 8 * 8).coerceIn(0, 255)
        val mode = when {
            background >= 208 -> Mode.LIGHT
            background <= 64 -> Mode.DARK
            else -> return Palette(Mode.ORIGINAL)
        }
        val black = blackPoint(mode, level)
        val white = whitePoint(mode)
        // Match a substantial solid neutral shade, not isolated antialiasing pixels.
        // This reads a tone only; it does not infer lane geometry or recommendation.
        val secondary = (0..255).filter { (it - black) / (white - black) in 0.15f..0.85f }
            .maxByOrNull { histogram[it] }
            ?.takeIf { histogram[it] >= max(4, pixels.size / 1000) }
        return Palette(mode, level, secondary)
    }

    fun filters(palette: Palette): Filters? {
        if (palette.mode == Mode.ORIGINAL) return null
        // Clamp the neutral background to zero while retaining antialiased edges and grey lanes.
        val black = blackPoint(palette.mode, palette.backgroundLevel)
        val white = whitePoint(palette.mode)
        val scale = 255f / (white - black)
        val offset = -black * scale
        val r = .2126f * scale; val g = .7152f * scale; val b = .0722f * scale
        val linear = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                r, g, b, 0f, offset,
                r, g, b, 0f, offset,
                r, g, b, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )))
        val knee = palette.secondaryLevel?.let { (it - black) / (white - black) }
            ?.takeIf { it in 0.15f..0.85f }
            ?: return Filters(Paint().apply { colorFilter = linear })
        // Two clamped stages give a continuous three-point curve:
        // background -> 0, source secondary grey -> 102, active white -> 255.
        // A single matrix cannot meet all three anchors for both phone themes.
        val tail = 1f - knee
        val channels = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            r, g, b, 0f, offset,
            r / tail, g / tail, b / tail, 0f, (offset - knee * 255f) / tail,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        val low = NON_RECOMMENDED_LEVEL.toFloat() / (RECOMMENDED_LEVEL * knee)
        val high = 1f - low
        val combine = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            low, high, 0f, 0f, 0f,
            low, high, 0f, 0f, 0f,
            low, high, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )))
        return Filters(Paint().apply { colorFilter = channels }, Paint().apply { colorFilter = combine })
    }

    private fun blackPoint(mode: Mode, level: Int) = if (mode == Mode.LIGHT) level - 8f else level + 8f
    private fun whitePoint(mode: Mode) = if (mode == Mode.LIGHT) 16f else 224f

    private fun luminance(color: Int) =
        .2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color)
    private fun chroma(color: Int) =
        max(Color.red(color), max(Color.green(color), Color.blue(color))) -
            min(Color.red(color), min(Color.green(color), Color.blue(color)))
}

/** Probe the unfiltered texture; put the filter on its parent to avoid sampling our own output. */
internal class MzdHudCardToneController(
    private val texture: TextureView,
    private val viewport: View,
    private val sourceLayer: View,
    private val targetLayer: View,
    private val report: (String) -> Unit,
) : AutoCloseable {
    private var probe: Bitmap? = null
    private var palette: MzdHudCardTone.Palette? = null
    private var pending = false
    private var closed = false
    private var lastProbeAt: Long? = null
    private val sample = Runnable {
        pending = false
        if (!closed && texture.isAvailable) {
            lastProbeAt = SystemClock.uptimeMillis()
            try {
                val bitmap = probe ?: Bitmap.createBitmap(MzdHudCardTone.PROBE_SIZE,
                    MzdHudCardTone.PROBE_SIZE, Bitmap.Config.ARGB_8888).also { probe = it }
                bitmap.eraseColor(Color.TRANSPARENT)
                texture.getBitmap(bitmap)
                MzdHudCardTone.detect(bitmap)?.let { next ->
                    if (palette != next) {
                        palette = next
                        val filters = MzdHudCardTone.filters(next)
                        sourceLayer.setLayerType(if (filters == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
                            filters?.source)
                        targetLayer.setLayerType(if (filters?.target == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
                            filters?.target)
                        report("hudmap action=card_tone mode=${next.mode.name.lowercase(java.util.Locale.ROOT)}")
                    }
                    texture.alpha = 1f
                }
            } catch (_: RuntimeException) {
                // Retain the last good palette (or the initial black cover), retry on a later frame.
            }
        }
    }

    fun frameAvailable() {
        if (closed || pending) return
        val previous = lastProbeAt
        if (previous != null && SystemClock.uptimeMillis() - previous < MzdHudCardTone.PROBE_INTERVAL_MILLIS) return
        pending = true
        // onSurfaceTextureUpdated can run during draw. Readback must be a separate UI callback.
        texture.post(sample)
    }

    override fun close() {
        closed = true
        texture.removeCallbacks(sample)
        probe?.recycle()
        probe = null
    }
}
