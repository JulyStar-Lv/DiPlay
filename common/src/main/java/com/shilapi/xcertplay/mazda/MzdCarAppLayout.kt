package com.shilapi.xcertplay.mazda

import android.annotation.SuppressLint
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
import android.widget.FrameLayout
import kotlin.math.abs
import androidx.core.graphics.drawable.toDrawable
import androidx.core.content.ContextCompat

/** EZ60 driver panel coordinates, in physical host pixels. */
internal class MzdCarAppLayout(context: Context) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var vehiclePanel = readVehiclePanel()
    private var targetBounds: Rect? = null
    private var embedded = false
    private val windowOrigin = IntArray(2)
    private val layoutBounds = Rect()
    private val panelClip = Path()
    private val panelOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, PANEL_CORNER_RADIUS_PX)
        }
    }
    private var observing = false
    private var navigationReceiverRegistered = false
    private val navigationReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS ||
                intent.getStringExtra("reason") != "TinnoveCarSystemBarButton") return
            // The OEM adds a nonstandard area extra. Match its value without guessing its key.
            val extras = intent.extras ?: return
            if (extras.keySet().none { extras.get(it) == "area_left" }) return
            val activity = this@MzdCarAppLayout.context as? Activity ?: return
            if (!isCockpitWindow(activity) || activity.isFinishing) return
            // The OEM can keep both transparent tasks visible after moveTaskToBack(). Close their
            // windows instead, like the CarPlay car button; the background session outlives the UI.
            Log.i("DiPlayUi", "driver navigation requested; closing cockpit windows, retaining session")
            MzdIntegration.closeWindows()
        }
    }
    private val refresh = Runnable {
        vehiclePanel = readVehiclePanel()
        requestLayout()
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 16)
        }
    }

    fun setPanelContent(view: View) {
        removeAllViews()
        addView(view, LayoutParams(-1, -1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vehiclePanel = readVehiclePanel()
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
        if (navigationReceiverRegistered) context.unregisterReceiver(navigationReceiver)
        navigationReceiverRegistered = false
        handler.removeCallbacks(refresh)
        if (observing) context.contentResolver.unregisterContentObserver(observer)
        observing = false
        super.onDetachedFromWindow()
    }

    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cockpit = isCockpitSize(width, height) ||
            (context is Activity && isCockpitWindow(context as Activity) && width >= 2560 && height >= 1272)
        val bounds = if (cockpit) Rect(if (vehiclePanel) 832 else 64, 192, 2560, 1272)
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
            layoutBounds.offset(-windowOrigin[0], -windowOrigin[1])
        }
        val bounds = layoutBounds
        panelClip.rewind()
        panelClip.addRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
            bounds.bottom.toFloat(), PANEL_CORNER_RADIUS_PX, PANEL_CORNER_RADIUS_PX, Path.Direction.CW)
        getChildAt(0)?.apply {
            // Change the viewport once; independent host/iPhone animations expose the unused video region.
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
        Settings.Global.getInt(context.contentResolver, "key_screen_show", 0) != 0 ||
            Settings.Global.getInt(context.contentResolver, "key_vpa_cui_show_left", 0) == 1
    } catch (_: SecurityException) { false }

    companion object {
        // Like the cockpit bounds, this radius is in physical pixels and does not scale with density.
        private const val PANEL_CORNER_RADIUS_PX = 8f

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
