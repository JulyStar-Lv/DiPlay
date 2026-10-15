package com.shilapi.xcertplay.mazda

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.core.graphics.drawable.toDrawable
import androidx.core.content.ContextCompat

/** EZ60 panel coordinates, in physical host pixels, relative to the current task area. */
internal class MzdCarAppLayout(context: Context) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var passengerPanel = false
    private var vehiclePanel = readVehiclePanel()
    private var targetBounds: Rect? = null
    private var embedded = false
    private var panelProgress = 0f
    private val panelEasing = AccelerateDecelerateInterpolator()
    private val panelLeft: Int
        get() = if (passengerPanel) PASSENGER_PANEL_MARGIN_PX else FULL_PANEL_LEFT_PX +
            ((NARROW_PANEL_LEFT_PX - FULL_PANEL_LEFT_PX) * panelEasing.getInterpolation(panelProgress)).roundToInt()
    private var panelAnimator: ValueAnimator? = null
    private val windowOrigin = IntArray(2)
    private val laidOutOrigin = IntArray(2)
    private val layoutBounds = Rect()
    private val panelClip = Path()
    private val backdropBounds = Rect()
    private val backdropClip = Path()
    private var transitionBackdrop: Bitmap? = null
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clearBackdrop = Runnable {
        transitionBackdrop = null
        invalidate()
    }
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
        panelProgress = desiredPanelProgress()
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
        releaseBackdrop()
        panelProgress = desiredPanelProgress()
        if (navigationReceiverRegistered) context.unregisterReceiver(navigationReceiver)
        navigationReceiverRegistered = false
        handler.removeCallbacks(refresh)
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // AppList jumps to the current state off screen, rather than replaying a resize on return.
        // Use visibility, not focus: an unfocused passenger/multi-window panel can still be visible.
        panelAnimator?.cancel()
        panelAnimator = null
        releaseBackdrop()
        vehiclePanel = readVehiclePanel()
        panelProgress = desiredPanelProgress()
        requestLayout()
    }

    private fun desiredPanelProgress() = if (!passengerPanel && vehiclePanel) 1f else 0f

    private fun updateWindowArea(width: Int) {
        getLocationOnScreen(windowOrigin)
        val passenger = isPassengerArea(windowOrigin[0], width)
        if (passenger == passengerPanel) return
        passengerPanel = passenger
        panelAnimator?.cancel()
        panelAnimator = null
        releaseBackdrop()
        vehiclePanel = readVehiclePanel()
        panelProgress = desiredPanelProgress()
    }

    private fun movePanel() {
        panelAnimator?.cancel()
        panelAnimator = null
        val target = desiredPanelProgress()
        val backdropSource = if (panelProgress != target) {
            // Keep CarPlay's mask ahead of the first resize, including immediate state changes.
            val targetLeft = if (passengerPanel) PASSENGER_PANEL_MARGIN_PX
                else if (target == 1f) NARROW_PANEL_LEFT_PX else FULL_PANEL_LEFT_PX
            (context as? com.shilapi.xcertplay.CarPlayHostActivity)?.beginMazdaPanelTransition(PANEL_WIDTH_PX - targetLeft)
        } else null
        if (!embedded || !isLaidOut || !isShown || windowVisibility != VISIBLE ||
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            releaseBackdrop()
            panelProgress = target
            requestLayout()
            return
        }
        if (panelProgress == target) {
            // Reversal before the first frame cancels the animator without an end callback.
            releaseBackdrop()
            return
        }
        // AppList and the sidebar are separate windows/processes. Matching their curve cannot
        // synchronize their first frame: retain coverage of the swept area behind our moving edge.
        // The OEM sidebar is a higher-layer window, so it still draws and receives input above us.
        handler.removeCallbacks(clearBackdrop)
        if (transitionBackdrop == null) captureBackdrop(backdropSource)
        // Launcher AppList's applist_bg_scene.xml inherits 400 ms and interpolator 0 from
        // its bundled MotionScene. MotionLayout eases persistent linear 0..1 progress.
        // Continue that progress on reversal instead of easing a new pixel-distance segment:
        // otherwise a partly closed panel restarts its slow phase and finishes too early.
        panelAnimator = ValueAnimator.ofFloat(panelProgress, target).apply {
            duration = (PANEL_TRANSITION_MILLIS * abs(target - panelProgress)).roundToLong().coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener {
                panelProgress = it.animatedValue as Float
                requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    // Give the independent background window one complete transition to settle.
                    // Respect the device animation scale, including slow accessibility animations.
                    val scale = Settings.Global.getFloat(context.contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f).takeIf { it.isFinite() } ?: 1f
                    handler.postDelayed(clearBackdrop,
                        (PANEL_TRANSITION_MILLIS * scale.coerceAtLeast(0f)).roundToLong())
                }
            })
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
            releaseBackdrop()
        }
        if (panelAnimator?.isRunning != true) panelProgress = desiredPanelProgress()
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
        backdropBounds.set(bounds)
        if (embedded && !passengerPanel) {
            backdropBounds.left = FULL_PANEL_LEFT_PX - windowOrigin[0]
        }
        backdropClip.rewind()
        backdropClip.addRoundRect(backdropBounds.left.toFloat(), backdropBounds.top.toFloat(),
            backdropBounds.right.toFloat(), backdropBounds.bottom.toFloat(),
            PANEL_CORNER_RADIUS_PX, PANEL_CORNER_RADIUS_PX, Path.Direction.CW)
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
        transitionBackdrop?.let { bitmap ->
            if (embedded && !passengerPanel) {
                val save = canvas.save()
                canvas.clipPath(backdropClip)
                canvas.drawBitmap(bitmap, null, backdropBounds, backdropPaint)
                canvas.restoreToCount(save)
            }
        }
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

    private fun captureBackdrop(source: Bitmap?) {
        val content = getChildAt(0) ?: return
        if (passengerPanel || content.width <= 0 || content.height <= 0) return
        // Deliberately low resolution: the exposed sliver should be a neutral, blurred continuation,
        // rather than duplicate readable controls. No screenshot/overlay permissions are needed.
        val bitmap = Bitmap.createBitmap(24,
            (24 * content.height / content.width).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(31, 54, 73))
        val canvas = Canvas(bitmap)
        if (source != null && !source.isRecycled) {
            canvas.drawBitmap(source, null, Rect(0, 0, bitmap.width, bitmap.height), backdropPaint)
        } else {
            canvas.scale(bitmap.width.toFloat() / content.width, bitmap.height.toFloat() / content.height)
            content.draw(canvas)
        }
        // SurfaceView punches a transparent hole when drawn into a software canvas. Flatten
        // that hole after capture too; pre-filling the bitmap alone cannot keep it opaque.
        canvas.drawColor(Color.rgb(31, 54, 73), PorterDuff.Mode.DST_OVER)
        transitionBackdrop = bitmap
    }

    private fun releaseBackdrop() {
        handler.removeCallbacks(clearBackdrop)
        transitionBackdrop = null
        invalidate()
    }

    private fun readVehiclePanel(): Boolean = try {
        // Launcher CommonUtil.getScreenStatus treats either nonzero OEM state as narrow.
        !passengerPanel && (Settings.Global.getInt(context.contentResolver, "key_screen_show", 0) != 0 ||
            Settings.Global.getInt(context.contentResolver, "key_vpa_cui_show_left", 0) != 0)
    } catch (_: SecurityException) { false }

    companion object {
        // Like the cockpit bounds, this radius is in physical pixels and does not scale with density.
        private const val PANEL_CORNER_RADIUS_PX = 8f
        private const val PANEL_WIDTH_PX = 2560
        // OEM passenger apps have equal margins; the driver's left margin is twice this size.
        private const val PASSENGER_PANEL_MARGIN_PX = 32
        private const val FULL_PANEL_LEFT_PX = 64
        private const val NARROW_PANEL_LEFT_PX = 832
        // Verified against WT_NewLauncher 2.1.0.616_alph, not the separate 500 ms entry fade.
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
