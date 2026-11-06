package com.shilapi.xcertplay.mazda

import android.app.Activity
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.InterfaceSize
import com.shilapi.xcertplay.host.R

internal class MzdConnectionView(
    context: Context, night: Boolean, close: () -> Unit, settings: () -> Unit,
    back: () -> Unit, wireless: Boolean,
) : MzdPageView(context, night, close, settings) {
    init {
        stage.text = context.getString(R.string.getting_carplay_ready)
        instructions.text = context.getString(if (wireless)
            R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
        else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
        addAction(context.getString(R.string.back_to_diplay), click = back)
    }

    fun replace(previous: View) {
        val parent = previous.parent as FrameLayout
        val index = parent.indexOfChild(previous)
        parent.removeView(previous)
        parent.addView(this, index, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val safe = if (MzdIntegration.cockpit(parent.context)) androidx.core.graphics.Insets.NONE else
                insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    companion object {
        fun preparationContext(activity: Activity): Context {
            if (!MzdIntegration.enabled(activity)) return activity
            // Scale only the preparation UI; video and touch retain the host's window metrics.
            return ContextThemeWrapper(activity, 0).apply {
                InterfaceSize.attach(this, activity)?.let { InterfaceSize.enforce(resources, it) }
            }
        }
    }
}
