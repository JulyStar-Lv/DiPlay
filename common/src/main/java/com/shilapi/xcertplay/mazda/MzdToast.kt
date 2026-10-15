package com.shilapi.xcertplay.mazda

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

/** A foreground toast inside the current panel, without an OEM overlay permission or shared UID. */
internal class MzdToast(private val host: FrameLayout) {
    private var messageView: TextView? = null
    private val curve = PathInterpolator(.25f, .1f, .25f, 1f)
    private val expire = Runnable { dismiss(animated = true) }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = dismiss(animated = false)
        })
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWidth() }
    }

    fun show(message: String, night: Boolean) {
        if (!host.isAttachedToWindow || !host.isShown || host.windowVisibility != View.VISIBLE) return
        dismiss(animated = false)
        val resources = host.resources
        val horizontal = resources.getDimensionPixelSize(R.dimen.mazda_toast_padding_horizontal)
        val vertical = resources.getDimensionPixelSize(R.dimen.mazda_toast_padding_vertical)
        val view = TextView(host.context).apply {
            tag = VIEW_TAG
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.mazda_toast_text_size) * resources.configuration.fontScale)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(if (night) 0xe6ffffff.toInt() else 0xe60b0f14.toInt())
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                setColor(if (night) Color.rgb(38, 40, 44) else Color.rgb(245, 248, 250))
                cornerRadius = resources.getDimension(R.dimen.mazda_toast_corner_radius)
            }
            elevation = resources.getDimension(R.dimen.mazda_toast_elevation)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            alpha = 0f
        }
        messageView = view
        updateWidth()
        host.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.mazda_toast_bottom_margin)
        })
        view.animate().alpha(1f).setDuration(ANIMATION_MILLIS).setInterpolator(curve).start()
        host.postDelayed(expire, DURATION_MILLIS)
    }

    private fun updateWidth() {
        val view = messageView ?: return
        val resources = host.resources
        val margin = resources.getDimensionPixelSize(R.dimen.mazda_toast_bottom_margin)
        val available = (host.width - margin * 2).coerceAtLeast(1)
        val maxText = if (resources.configuration.locales[0].language == "zh")
            view.paint.measureText("中".repeat(20)).roundToInt()
        else resources.getDimensionPixelSize(R.dimen.mazda_toast_max_text_width)
        val width = min(available, maxText + view.paddingLeft + view.paddingRight)
        if (view.maxWidth != width) view.maxWidth = width
    }

    private fun dismiss(animated: Boolean) {
        host.removeCallbacks(expire)
        val view = messageView ?: return
        view.animate().cancel()
        if (animated) {
            view.animate().alpha(0f).scaleX(.8f).scaleY(.8f)
                .setDuration(ANIMATION_MILLIS).setInterpolator(curve).withEndAction {
                    host.removeView(view)
                    if (messageView === view) messageView = null
                }.start()
        } else {
            host.removeView(view)
            messageView = null
        }
    }

    companion object {
        internal const val VIEW_TAG = "mazda_foreground_toast"
        private const val DURATION_MILLIS = 3500L
        private const val ANIMATION_MILLIS = 266L
    }
}
