package com.shilapi.xcertplay.mazda

import android.graphics.Color
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Blurred last active area, with opaque fallback, until two new target presentations are ready. */
internal class MzdTransitionMask(
    parent: FrameLayout,
    coveredView: View,
    private val handler: Handler,
    private val checkFrame: ((Boolean) -> Unit) -> Unit,
    private val requestPicture: () -> Unit,
    private val renderedFrames: () -> Long,
    private var textureOutput: Boolean,
    private val retryLayout: () -> Unit = {},
    private val surfaceAvailable: () -> Boolean = { true },
) {
    private var updatingPicture = false
    val view = object : ImageView(parent.context) {
        override fun requestLayout() {
            // A late asynchronous snapshot changes pixels, never the prepared animation layout.
            if (!updatingPicture) super.requestLayout() else invalidate()
        }
    }.apply {
        scaleType = ImageView.ScaleType.FIT_XY
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(BACKGROUND_COLOR)
        visibility = View.GONE
    }
    val isShowing: Boolean get() = view.visibility == View.VISIBLE
    val hasPicture: Boolean get() = view.drawable != null
    fun showPicture(picture: Bitmap) {
        if (!isShowing || fading) return
        updatingPicture = true
        try { view.setImageBitmap(picture) } finally { updatingPicture = false }
    }
    private var generation = 0
    private var targetWidth: Int? = null
    private var areaRequestedAt: Long? = null
    private var settled = false
    private var waitingForFrame = false
    private var fading = false
    private var fadeEnded = false
    private var checking = false
    private var readyFrames = 0
    private var textureFrames = 0L
    private var baseline = -1L
    private fun presentations() = if (textureOutput) textureFrames else renderedFrames()
    private val arm = Runnable {
        if (!isShowing || !settled) return@Runnable
        baseline = presentations()
        waitingForFrame = true
        requestPicture()
        handler.postDelayed(poll, FRAME_POLL_MILLIS)
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!isShowing || !waitingForFrame) return
            inspectFrame()
            if (waitingForFrame) handler.postDelayed(this, FRAME_POLL_MILLIS)
        }
    }

    private fun inspectFrame() {
        if (!isShowing || !waitingForFrame || checking) return
        val frame = presentations()
        // Surface recreation can restart the decoder's counter. A different positive count
        // is a new presentation too; requiring it to exceed the old total can stall forever.
        if (frame <= 0 || frame == baseline) return
        baseline = frame
        checking = true
        val token = generation
        checkFrame { ready ->
            if (token != generation || !isShowing || !waitingForFrame) return@checkFrame
            checking = false
            readyFrames = if (ready) readyFrames + 1 else 0
            if (!ready && fading) {
                // An iPhone area animation may regress after its first two matching frames.
                // Cancel the pending hide and restore the SAME blurred picture immediately.
                ++generation
                view.animate().cancel()
                view.alpha = 1f
                fading = false
                fadeEnded = false
                handler.postDelayed(timeout, RETRY_MILLIS)
                requestPicture()
            } else if (fading && fadeEnded) completeFade()
            else if (readyFrames >= REQUIRED_READY_FRAMES) finish()
            // A static page needs another presentation too; never count the same buffer twice.
            else if (ready) requestPicture()
        }
    }
    private val timeout = object : Runnable {
        override fun run() {
            if (!isShowing || fading) return
            // Neither elapsed time nor a new frame proves that the target area has arrived.
            retryLayout()
            requestPicture()
            handler.removeCallbacks(this)
            if (isShowing && !fading) handler.postDelayed(this, RETRY_MILLIS)
        }
    }
    private val recover = Runnable {
        if (isShowing && surfaceAvailable()) {
            // Pixel colour is evidence, not an acknowledgement: a valid dark screen can fail it.
            // Keep the session and controls usable even if copying/area negotiation never recovers.
            Log.w("DiPlayUi", "Mazda transition guard expired; restoring video and touch")
            retryLayout()
            requestPicture()
            finish(validateDuringFade = false)
        }
    }

    init {
        // Cover only the current viewport; the OEM owns the area outside the moving edge.
        parent.addView(view, parent.indexOfChild(coveredView) + 1, FrameLayout.LayoutParams(-1, -1))
    }

    fun begin(width: Int? = null) {
        ++generation
        targetWidth = width
        areaRequestedAt = null
        cancelCallbacks()
        view.animate().cancel()
        settled = false
        waitingForFrame = false
        fading = false
        fadeEnded = false
        checking = false
        readyFrames = 0
        view.alpha = 1f
        view.visibility = View.VISIBLE
        handler.postDelayed(timeout, START_TIMEOUT_MILLIS)
        handler.postDelayed(recover, MAX_TRANSITION_MILLIS)
    }

    fun areaRequested(width: Int? = null) {
        if (!isShowing || areaRequestedAt != null || (targetWidth != null && width != targetWidth)) return
        areaRequestedAt = SystemClock.uptimeMillis()
    }

    fun settle(width: Int? = null) {
        if (!isShowing || settled || (targetWidth != null && width != targetWidth)) return
        settled = true
        areaRequested(width)
        handler.removeCallbacks(timeout)
        // Overlap the iPhone's 300 ms area animation with the OEM panel movement. The endpoint
        // still gates inspection, but must not start another full guard after a 400 ms resize.
        val remaining = (VIDEO_SETTLE_MILLIS - (SystemClock.uptimeMillis() - requireNotNull(areaRequestedAt)))
            .coerceAtLeast(0L)
        handler.postDelayed(arm, remaining)
        handler.postDelayed(timeout, SETTLED_TIMEOUT_MILLIS)
    }

    fun textureUpdated() {
        ++textureFrames
        // A presentation callback is already on the UI thread; do not add a 50 ms poll delay.
        if (textureOutput) inspectFrame()
    }

    fun surfaceChanged(textureOutput: Boolean = this.textureOutput) {
        this.textureOutput = textureOutput
        if (!isShowing) return
        // Frames and a pending fade belong to the old Surface. Keep its blurred picture,
        // restore full opacity and require two presentations from the replacement instead.
        val wasSettled = settled
        begin(targetWidth)
        if (wasSettled) settle(targetWidth)
    }

    fun dispose() {
        ++generation
        cancelCallbacks()
        view.animate().cancel()
        view.visibility = View.GONE
        view.setImageDrawable(null)
        waitingForFrame = false
        fading = false
        checking = false
        (view.parent as? FrameLayout)?.removeView(view)
    }

    private fun finish(validateDuringFade: Boolean = true) {
        if (!isShowing || !surfaceAvailable()) return
        // Preserve the original bounded escape for valid dark/unreadable screens, even if
        // a pending PixelCopy or repeated edge regression would otherwise prolong the fade.
        if (!validateDuringFade) {
            waitingForFrame = false
            checking = false
            handler.removeCallbacks(poll)
            handler.removeCallbacks(recover)
        }
        if (fading) {
            if (fadeEnded && !checking) completeFade()
            return
        }
        fading = true
        fadeEnded = false
        val token = generation
        handler.removeCallbacks(arm)
        handler.removeCallbacks(timeout)
        // Keep checking distinct presentations throughout the fade; do not reveal a bad
        // edge simply because it arrived after the two presentations that started fading.
        view.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction {
            if (generation == token) {
                if (surfaceAvailable()) {
                    fadeEnded = true
                    // A newer presentation is still being copied. Let its result decide
                    // whether to hide or restore the blur, rather than ignoring it as stale.
                    if (!checking) completeFade()
                } else surfaceChanged()
            }
        }.start()
    }

    private fun completeFade() {
        cancelCallbacks()
        waitingForFrame = false
        fading = false
        fadeEnded = false
        view.visibility = View.GONE
        view.setImageDrawable(null)
    }

    private fun cancelCallbacks() {
        handler.removeCallbacks(arm)
        handler.removeCallbacks(poll)
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(recover)
    }

    companion object {
        // Only used when neither the current texture nor a previous blurred frame is available.
        val BACKGROUND_COLOR: Int = Color.rgb(32, 32, 32)
        private const val REQUIRED_READY_FRAMES = 2
        private const val VIDEO_SETTLE_MILLIS = 450L
        private const val FRAME_POLL_MILLIS = 50L
        private const val SETTLED_TIMEOUT_MILLIS = 1500L
        private const val START_TIMEOUT_MILLIS = 2500L
        private const val RETRY_MILLIS = 1000L
        private const val FADE_MILLIS = 150L
        private const val MAX_TRANSITION_MILLIS = 8000L
    }
}
