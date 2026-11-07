package com.shilapi.vehicle.mzd

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.display.api.NavigationState
import java.util.concurrent.atomic.AtomicReference

internal fun interface MzdNavigationOutput {
    fun submit(state: NavigationState?)
}

/** Physical ordinary HUD only. A local View never negotiates a phone video stream. */
internal object MzdHudProfile {
    const val SIZE = 178
    const val LEFT = 584
    const val TOP = 179

    fun accepts(name: String, width: Int, height: Int, rotation: Int, id: Int, valid: Boolean,
        hudType: Int? = null): Boolean =
        (name == "MeterDisplay#0" || name == "HDMI 屏幕") && (hudType == null || hudType == 2) &&
            width == 800 && height == 480 && rotation == Surface.ROTATION_0 &&
            id != Display.DEFAULT_DISPLAY && valid
}

internal class MzdHudTbtOutput(context: Context, private val report: (String) -> Unit,
    private val hudType: () -> Int? = ::readMzdHudType,
) : MzdNavigationOutput {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = this.context.getSystemService(DisplayManager::class.java)
    private val state = AtomicReference<NavigationState?>()
    private var listening = false
    private var presentation: MzdHudTbtPresentation? = null // main thread only
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()
        override fun onDisplayRemoved(displayId: Int) = reconcile()
        override fun onDisplayChanged(displayId: Int) = reconcile()
    }

    override fun submit(state: NavigationState?) {
        if (this.state.getAndSet(state) != state) handler.post { reconcile() }
    }

    @Suppress("DEPRECATION")
    private fun target(): Display? {
        val displays = manager?.displays.orEmpty()
        val type = hudType()
        return displays.filter {
            val metrics = DisplayMetrics().also(it::getRealMetrics)
            MzdHudProfile.accepts(it.name, metrics.widthPixels, metrics.heightPixels, it.rotation,
                it.displayId, it.isValid, type) && it.state != Display.STATE_OFF
        }.singleOrNull()
    }

    private fun reconcile() {
        val value = state.get()
        if (value == null) {
            dismiss()
            if (listening) manager?.unregisterDisplayListener(listener)
            listening = false
            return
        }
        if (!listening) { manager?.registerDisplayListener(listener, handler); listening = true }
        val display = target()
        if (display == null) { dismiss(); return }
        if (presentation?.display?.displayId != display.displayId) {
            dismiss()
            val next = MzdHudTbtPresentation(context, display)
            try {
                next.show()
                presentation = next
                report("hudtbt action=window display=${display.displayId} viewport=584,179,178,178")
            } catch (error: RuntimeException) {
                next.dismiss()
                report("hudtbt action=unavailable error=${error.javaClass.simpleName}")
            }
        }
        presentation?.submit(value)
    }

    private fun dismiss() {
        val previous = presentation
        presentation = null
        if (previous != null) { previous.dismiss(); report("hudtbt action=cleared") }
    }
}

private class MzdHudTbtPresentation(context: Context, display: Display) :
    Presentation(context, display, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
    private var state: NavigationState? = null
    private val renderer = MzdHudTbtRenderer(context)
    private val card = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            if (renderer.draw(canvas, state)) postInvalidateDelayed(33)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            decorView.setPadding(0, 0, 0, 0)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(card, FrameLayout.LayoutParams(MzdHudProfile.SIZE, MzdHudProfile.SIZE,
                Gravity.TOP or Gravity.LEFT).apply { leftMargin = MzdHudProfile.LEFT; topMargin = MzdHudProfile.TOP })
        }
        setContentView(root)
        window?.apply { setLayout(-1, -1); setGravity(Gravity.TOP or Gravity.LEFT) }
    }

    fun submit(state: NavigationState) { this.state = state; card.invalidate() }
}
