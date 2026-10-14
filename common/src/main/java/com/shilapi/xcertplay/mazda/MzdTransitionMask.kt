package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** An opaque, blurred backdrop from the first resize signal until post-transition video arrives. */
internal class MzdTransitionMask(
    private val parent: FrameLayout,
    output: View,
    private val handler: Handler,
    private val capture: ((Bitmap?) -> Unit) -> Unit,
    private val requestPicture: () -> Unit,
    private val renderedFrames: () -> Long,
    private val textureOutput: Boolean,
) {
    val picture = ImageView(parent.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }
    val blur = ImageView(parent.context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0.92f
    }
    val view = FrameLayout(parent.context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(31, 54, 73), Color.rgb(14, 28, 46)))
        foreground = android.graphics.drawable.ColorDrawable(0x330D1725)
        // Stretch the complete active picture horizontally as the window widens. The tiny,
        // filtered overlay supplies blur without a RenderEffect layer cached at the old width.
        addView(picture, FrameLayout.LayoutParams(-1, -1))
        addView(blur, FrameLayout.LayoutParams(-1, -1))
        visibility = View.GONE
    }
    val isShowing: Boolean get() = view.visibility == View.VISIBLE
    private var generation = 0
    private var targetWidth: Int? = null
    private var settled = false
    private var waitingForFrame = false
    private var baseline = -1L
    private val arm = Runnable {
        if (!isShowing || !settled) return@Runnable
        baseline = renderedFrames()
        waitingForFrame = true
        // A still CarPlay page may otherwise stop sending frames after its area animation.
        requestPicture()
        handler.postDelayed(poll, FRAME_POLL_MILLIS)
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!isShowing || !waitingForFrame) return
            if (!textureOutput && renderedFrames() > baseline) finish()
            else handler.postDelayed(this, FRAME_POLL_MILLIS)
        }
    }
    private val timeout = Runnable { finish() }

    init {
        // Fill the viewport, not the fitted/overscanned video bounds. Touch/settings stay above it.
        parent.addView(view, parent.indexOfChild(output) + 1, FrameLayout.LayoutParams(-1, -1))
    }

    fun begin(width: Int? = null) {
        val alreadyShowing = isShowing
        val token = ++generation
        targetWidth = width
        cancelCallbacks()
        view.animate().cancel()
        settled = false
        waitingForFrame = false
        view.alpha = 1f
        view.visibility = View.VISIBLE
        // Covers missing endpoints too (e.g. the OEM moves the task during the animation).
        handler.postDelayed(timeout, START_TIMEOUT_MILLIS)
        if (!alreadyShowing || picture.drawable == null) capture { captured ->
            if (generation == token && isShowing && captured != null && !captured.isRecycled) {
                picture.setImageBitmap(captured)
                val height = (SNAPSHOT_WIDTH * captured.height / captured.width).coerceAtLeast(1)
                blur.setImageBitmap(Bitmap.createScaledBitmap(captured, SNAPSHOT_WIDTH, height, true))
                (blur.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
            }
        }
    }

    fun settle(width: Int? = null) {
        if (!isShowing || settled || (targetWidth != null && width != targetWidth)) return
        settled = true
        handler.removeCallbacks(timeout)
        // The area command is asynchronous and asks the iPhone for a 300 ms animation.
        // Do not treat the first old/narrow frame after the command as a completed switch.
        handler.postDelayed(arm, VIDEO_SETTLE_MILLIS)
        handler.postDelayed(timeout, SETTLED_TIMEOUT_MILLIS)
    }

    fun textureUpdated() {
        if (isShowing && waitingForFrame && textureOutput) finish()
    }

    fun dispose() {
        ++generation
        cancelCallbacks()
        view.animate().cancel()
        view.visibility = View.GONE
        clearPicture()
        parent.removeView(view)
    }

    private fun finish() {
        if (!isShowing) return
        val token = generation
        cancelCallbacks()
        waitingForFrame = false
        view.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction {
            if (generation == token) {
                view.visibility = View.GONE
                clearPicture()
            }
        }.start()
    }

    private fun cancelCallbacks() {
        handler.removeCallbacks(arm)
        handler.removeCallbacks(poll)
        handler.removeCallbacks(timeout)
    }

    private fun clearPicture() {
        picture.setImageDrawable(null)
        blur.setImageDrawable(null)
    }

    companion object {
        // A tiny, filtered image supplies the same blur on all supported head units.
        const val SNAPSHOT_WIDTH = 24
        private const val VIDEO_SETTLE_MILLIS = 450L
        private const val FRAME_POLL_MILLIS = 50L
        private const val SETTLED_TIMEOUT_MILLIS = 1500L
        private const val START_TIMEOUT_MILLIS = 2500L
        private const val FADE_MILLIS = 150L
    }
}
