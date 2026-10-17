package com.shilapi.xcertplay.mazda

import android.graphics.Color
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.FrameLayout

/** Neutral coverage until two new presentations match the settled CarPlay view area. */
internal class MzdTransitionMask(
    parent: FrameLayout,
    coveredView: View,
    private val handler: Handler,
    private val checkFrame: ((Boolean) -> Unit) -> Unit,
    private val requestPicture: () -> Unit,
    private val renderedFrames: () -> Long,
    private val textureOutput: Boolean,
    private val retryLayout: () -> Unit = {},
) {
    val view = View(parent.context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(BACKGROUND_COLOR)
        visibility = View.GONE
    }
    val isShowing: Boolean get() = view.visibility == View.VISIBLE
    private var generation = 0
    private var targetWidth: Int? = null
    private var settled = false
    private var waitingForFrame = false
    private var fading = false
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
            val frame = presentations()
            // Surface recreation can restart the decoder's counter. A different positive count
            // is a new presentation too; requiring it to exceed the old total can stall forever.
            if (!checking && frame > 0 && frame != baseline) {
                baseline = frame
                checking = true
                val token = generation
                checkFrame { ready ->
                    if (token != generation || !isShowing || !waitingForFrame) return@checkFrame
                    checking = false
                    readyFrames = if (ready) readyFrames + 1 else 0
                    if (readyFrames >= REQUIRED_READY_FRAMES) finish()
                    // A static page needs another presentation too; never count the same buffer twice.
                    else if (ready) requestPicture()
                }
            }
            if (waitingForFrame) handler.postDelayed(this, FRAME_POLL_MILLIS)
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
        if (isShowing && !fading) {
            // Pixel colour is evidence, not an acknowledgement: a valid dark screen can fail it.
            // Keep the session and controls usable even if copying/area negotiation never recovers.
            Log.w("DiPlayUi", "Mazda transition guard expired; restoring video and touch")
            retryLayout()
            requestPicture()
            finish()
        }
    }

    init {
        // Cover only the current viewport; the shell separately covers the swept left edge.
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
        checking = false
        readyFrames = 0
        view.alpha = 1f
        view.visibility = View.VISIBLE
        handler.postDelayed(timeout, START_TIMEOUT_MILLIS)
        handler.postDelayed(recover, MAX_TRANSITION_MILLIS)
    }

    fun settle(width: Int? = null) {
        if (!isShowing || settled || (targetWidth != null && width != targetWidth)) return
        settled = true
        handler.removeCallbacks(timeout)
        // Keep the minimum guard for the iPhone's 300 ms area animation, then inspect pixels.
        handler.postDelayed(arm, VIDEO_SETTLE_MILLIS)
        handler.postDelayed(timeout, SETTLED_TIMEOUT_MILLIS)
    }

    fun textureUpdated() { ++textureFrames }

    fun dispose() {
        ++generation
        cancelCallbacks()
        view.animate().cancel()
        view.visibility = View.GONE
        waitingForFrame = false
        fading = false
        checking = false
        (view.parent as? FrameLayout)?.removeView(view)
    }

    private fun finish() {
        if (!isShowing || fading) return
        fading = true
        val token = generation
        cancelCallbacks()
        waitingForFrame = false
        view.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction {
            if (generation == token) view.visibility = View.GONE
        }.start()
    }

    private fun cancelCallbacks() {
        handler.removeCallbacks(arm)
        handler.removeCallbacks(poll)
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(recover)
    }

    companion object {
        val BACKGROUND_COLOR: Int = Color.rgb(31, 54, 73)
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
