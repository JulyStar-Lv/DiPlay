package com.shilapi.xcertplay.mazda

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable
import androidx.core.content.ContextCompat

/** EZ60 panel coordinates, in physical host pixels, relative to the current task area. */
internal class MzdCarAppLayout(context: Context) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var passengerPanel = false
    private var vehiclePanel = readVehiclePanel()
    private var targetBounds: Rect? = null
    private var embedded = false
    private var panelLeft = FULL_PANEL_LEFT_PX
    private var panelAnimator: ValueAnimator? = null
    private val windowOrigin = IntArray(2)
    private val laidOutOrigin = IntArray(2)
    private val layoutBounds = Rect()
    private val panelClip = Path()
    private val panelOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, PANEL_CORNER_RADIUS_PX)
        }
    }
    private var observing = false
    private var navigationReceiverRegistered = false
    private val windowPositionListener = ViewTreeObserver.OnPreDrawListener {
        getLocationOnScreen(windowOrigin)
        // Reparenting a task between the two halves can preserve its size and skip onMeasure.
        // Invalidate the coordinates before drawing, including changes to the decor's insets.
        if (embedded && (isPassengerArea(windowOrigin[0], width) != passengerPanel ||
                windowOrigin[0] != laidOutOrigin[0] || windowOrigin[1] != laidOutOrigin[1])) {
            updateWindowArea(width)
            requestLayout()
            false
        } else true
    }
    private val navigationReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS ||
                intent.getStringExtra("reason") != "TinnoveCarSystemBarButton") return
            // The OEM adds a nonstandard area extra. Match its value without guessing its key.
            val extras = intent.extras ?: return
            getLocationOnScreen(windowOrigin)
            val area = if (isPassengerArea(windowOrigin[0], width)) "area_right" else "area_left"
            if (extras.keySet().none { extras.get(it) == area }) return
            val activity = this@MzdCarAppLayout.context as? Activity ?: return
            if (!isCockpitWindow(activity) || activity.isFinishing) return
            // The OEM can keep both transparent tasks visible after moveTaskToBack(). Close their
            // windows instead, like the CarPlay car button; the background session outlives the UI.
            Log.i("DiPlayUi", "$area navigation requested; closing cockpit windows, retaining session")
            MzdIntegration.refreshOtherLauncherArea(activity)
            MzdIntegration.closeWindows()
        }
    }
    private val refresh = Runnable {
        val narrow = readVehiclePanel()
        if (vehiclePanel != narrow) {
            vehiclePanel = narrow
            movePanel()
        }
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }

    fun setPanelContent(view: View) {
        removeAllViews()
        addView(view, LayoutParams(-1, -1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(windowPositionListener)
        updateWindowArea(width)
        vehiclePanel = readVehiclePanel()
        panelLeft = desiredPanelLeft()
        val activity = context as? Activity
        if (!navigationReceiverRegistered && activity != null && isCockpitWindow(activity)) {
            ContextCompat.registerReceiver(context, navigationReceiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_EXPORTED)
            navigationReceiverRegistered = true
        }
        try {
            for (key in listOf("key_screen_show", "key_vpa_cui_show_left")) {
                context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            }
            observing = true
        } catch (_: SecurityException) {
            context.contentResolver.unregisterContentObserver(observer)
        }
        requestLayout()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(windowPositionListener)
        panelAnimator?.cancel()
        panelAnimator = null
        panelLeft = desiredPanelLeft()
        if (navigationReceiverRegistered) context.unregisterReceiver(navigationReceiver)
        navigationReceiverRegistered = false
        handler.removeCallbacks(refresh)
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        super.onDetachedFromWindow()
    }

    private fun desiredPanelLeft() = when {
        passengerPanel -> PASSENGER_PANEL_MARGIN_PX
        vehiclePanel -> NARROW_PANEL_LEFT_PX
        else -> FULL_PANEL_LEFT_PX
    }

    private fun updateWindowArea(width: Int) {
        getLocationOnScreen(windowOrigin)
        val passenger = isPassengerArea(windowOrigin[0], width)
        if (passenger == passengerPanel) return
        passengerPanel = passenger
        panelAnimator?.cancel()
        panelAnimator = null
        vehiclePanel = readVehiclePanel()
        panelLeft = desiredPanelLeft()
    }

    private fun movePanel() {
        panelAnimator?.cancel()
        panelAnimator = null
        val target = desiredPanelLeft()
        if (panelLeft != target) {
            // Show the mask before any resize or animator frame, including rapid reversals.
            (context as? com.shilapi.xcertplay.CarPlayHostActivity)?.beginMazdaPanelTransition(PANEL_WIDTH_PX - target)
        }
        if (!embedded || !isLaidOut || !isShown ||
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            panelLeft = target
            requestLayout()
            return
        }
        if (panelLeft == target) return
        // Match the OEM file manager's MotionScene: 400 ms, accelerate/decelerate. Jumping to
        // 832 before the sidebar slides in exposes the launcher's AppList below our transparent task.
        panelAnimator = ValueAnimator.ofInt(panelLeft, target).apply {
            duration = (PANEL_TRANSITION_MILLIS * abs(target - panelLeft) /
                (NARROW_PANEL_LEFT_PX - FULL_PANEL_LEFT_PX)).coerceAtLeast(1)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                panelLeft = it.animatedValue as Int
                requestLayout()
            }
            start()
        }
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        updateWindowArea(width)
        val cockpit = isCockpitSize(width, height) ||
            (context is Activity && isCockpitWindow(context as Activity) && width >= 2560 && height >= 1272)
        if (!cockpit) {
            panelAnimator?.cancel()
            panelAnimator = null
        }
        if (panelAnimator?.isRunning != true) panelLeft = desiredPanelLeft()
        val panelRight = PANEL_WIDTH_PX - if (passengerPanel) PASSENGER_PANEL_MARGIN_PX else 0
        val bounds = if (cockpit) Rect(panelLeft, 192, panelRight, 1272)
            else Rect(0, 0, width, height)
        targetBounds = bounds
        embedded = cockpit
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val target = targetBounds ?: return
        layoutBounds.set(target)
        if (embedded) {
            // OEM coordinates are physical screen coordinates, even when Android insets the decor.
            getLocationOnScreen(windowOrigin)
            val areaLeft = if (passengerPanel) PANEL_WIDTH_PX else 0
            layoutBounds.offset(areaLeft - windowOrigin[0], -windowOrigin[1])
            windowOrigin.copyInto(laidOutOrigin)
        }
        val bounds = layoutBounds
        panelClip.rewind()
        panelClip.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
            bounds.bottom.toFloat(), PANEL_CORNER_RADIUS_PX, PANEL_CORNER_RADIUS_PX, Path.Direction.CW)
        getChildAt(0)?.apply {
            // Move only the left edge. The fixed top, bottom and right keep OEM chrome uncovered.
            // CarPlay's existing resize debounce negotiates the settled view area, not each frame.
            layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            outlineProvider = panelOutline
            clipToOutline = embedded
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!embedded || canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
            return
        }
        // Software windows need the same small corners as hardware outline clipping.
        val save = canvas.save()
        canvas.clipPath(panelClip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun readVehiclePanel(): Boolean = try {
        !passengerPanel && (Settings.Global.getInt(context.contentResolver, "key_screen_show", 0) != 0 ||
            Settings.Global.getInt(context.contentResolver, "key_vpa_cui_show_left", 0) == 1)
    } catch (_: SecurityException) { false }

    companion object {
        // Like the cockpit bounds, this radius is in physical pixels and does not scale with density.
        private const val PANEL_CORNER_RADIUS_PX = 8f
        private const val PANEL_WIDTH_PX = 2560
        // OEM passenger apps have equal margins; the driver's left margin is twice this size.
        private const val PASSENGER_PANEL_MARGIN_PX = 32
        private const val FULL_PANEL_LEFT_PX = 64
        private const val NARROW_PANEL_LEFT_PX = 832
        private const val PANEL_TRANSITION_MILLIS = 400L

        private fun isPassengerArea(screenLeft: Int, width: Int) =
            width < PANEL_WIDTH_PX * 2 && screenLeft >= PANEL_WIDTH_PX

        fun applyWindowBackground(activity: Activity): Boolean {
            val cockpit = isCockpitWindow(activity)
            val color = if (cockpit) Color.TRANSPARENT else Color.rgb(12, 17, 27)
            activity.window.apply {
                // Inflate the decor before overriding the theme's system bar contrast.
                decorView
                setBackgroundDrawable(color.toDrawable())
                statusBarColor = color
                navigationBarColor = color
                if (Build.VERSION.SDK_INT >= 29) {
                    isStatusBarContrastEnforced = false
                    isNavigationBarContrastEnforced = !cockpit
                }
            }
            // A transparent drawable alone still lets the system hide the launcher's task and wallpaper.
            if (cockpit && Build.VERSION.SDK_INT >= 30 && !activity.isFinishing && activity.setTranslucent(true)) {
                Log.i("DiPlayUi", "cockpit window converted to translucent")
            }
            if (cockpit) androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            return cockpit
        }

        fun isCockpitWindow(activity: Activity): Boolean {
            val bounds = if (Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds
                else Rect(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
            return isCockpitSize(bounds.width(), bounds.height())
        }

        internal fun isCockpitSize(width: Int, height: Int): Boolean {
            if (width < 2560 || abs(height - 1440) > 72) return false
            val ratio = width.toFloat() / height
            return ratio in 1.70f..1.85f || ratio >= 3.40f
        }
    }
}
