package com.shilapi.xcertplay.mazda

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** An opaque, blurred backdrop from the first resize signal until post-transition video arrives. */
internal class MzdTransitionMask(
    private val parent: FrameLayout,
    coveredView: View,
    private val handler: Handler,
    private val capture: ((Bitmap?) -> Unit) -> Unit,
    private val requestPicture: () -> Unit,
    private val renderedFrames: () -> Long,
    private val textureOutput: Boolean,
    private val retryLayout: () -> Unit = {},
) {
    // The shell borrows the active-area snapshot for its swept-edge backdrop. Do not draw a
    // sharp copy under the blur: even a low-alpha copy leaves readable, stretched controls.
    var snapshot: Bitmap? = null
        private set
    val blur = ImageView(parent.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    val view = FrameLayout(parent.context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(31, 54, 73), Color.rgb(14, 28, 46)))
        foreground = android.graphics.drawable.ColorDrawable(0x330D1725)
        // Crop the blurred thumbnail to cover every width without distorting its shapes.
        // This also works below API 31, without a RenderEffect layer cached at the old width.
        addView(blur, FrameLayout.LayoutParams(-1, -1))
        visibility = View.GONE
    }
    val isShowing: Boolean get() = view.visibility == View.VISIBLE
    private var generation = 0
    private var targetWidth: Int? = null
    private var settled = false
    private var waitingForFrame = false
    private var fading = false
    private var capturePending = false
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
    private val timeout = object : Runnable {
        override fun run() {
            if (!isShowing || fading) return
            // A timer is not evidence that the video is ready. Keep covering a stalled stream,
            // recheck a missed/rejected endpoint and request another frame instead of exposing it.
            retryLayout()
            requestPicture()
            if (snapshot == null) captureSnapshot()
            // Retrying can synchronously settle the endpoint and schedule this callback again.
            handler.removeCallbacks(this)
            if (isShowing && !fading) handler.postDelayed(this, RETRY_MILLIS)
        }
    }

    init {
        // Fill the viewport, not the fitted/overscanned video bounds. Touch/settings stay above it.
        parent.addView(view, parent.indexOfChild(coveredView) + 1, FrameLayout.LayoutParams(-1, -1))
    }

    fun begin(width: Int? = null) {
        ++generation
        targetWidth = width
        cancelCallbacks()
        view.animate().cancel()
        settled = false
        waitingForFrame = false
        fading = false
        capturePending = false
        view.alpha = 1f
        view.visibility = View.VISIBLE
        // Covers missing endpoints too (e.g. the OEM moves the task during the animation).
        handler.postDelayed(timeout, START_TIMEOUT_MILLIS)
        if (snapshot == null) captureSnapshot()
    }

    private fun captureSnapshot() {
        if (capturePending) return
        capturePending = true
        val token = generation
        capture { captured ->
            if (generation != token || !isShowing || fading) return@capture
            capturePending = false
            if (captured != null && !captured.isRecycled) {
                snapshot = captured
                val height = (SNAPSHOT_WIDTH * captured.height / captured.width).coerceAtLeast(1)
                blur.setImageBitmap(Bitmap.createScaledBitmap(captured, SNAPSHOT_WIDTH, height, true))
                (blur.drawable as? BitmapDrawable)?.isFilterBitmap = true
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
        fading = false
        capturePending = false
        clearPicture()
        parent.removeView(view)
    }

    private fun finish() {
        if (!isShowing || fading) return
        fading = true
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
        snapshot = null
        blur.setImageDrawable(null)
    }

    companion object {
        // A tiny, filtered image supplies the same blur on all supported head units.
        const val SNAPSHOT_WIDTH = 24
        private const val VIDEO_SETTLE_MILLIS = 450L
        private const val FRAME_POLL_MILLIS = 50L
        private const val SETTLED_TIMEOUT_MILLIS = 1500L
        private const val START_TIMEOUT_MILLIS = 2500L
        private const val RETRY_MILLIS = 1000L
        private const val FADE_MILLIS = 150L
    }
}
