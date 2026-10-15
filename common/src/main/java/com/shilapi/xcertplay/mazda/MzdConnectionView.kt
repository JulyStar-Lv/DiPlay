package com.shilapi.xcertplay.mazda

import android.content.Context
import com.shilapi.xcertplay.host.R

internal class MzdConnectionView(
    context: Context, night: Boolean, close: () -> Unit, settings: () -> Unit,
    back: () -> Unit, wireless: Boolean,
) : MzdPageView(context, night, close, settings) {
    fun showHotspotFailure() = showToast(context.getString(R.string.hotspot_control_failed))

    init {
        stage.text = context.getString(R.string.getting_carplay_ready)
        instructions.text = context.getString(if (wireless)
            R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
        else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
        addAction(context.getString(R.string.back_to_diplay), click = back)
    }
}
