package com.shilapi.xcertplay.mazda

import android.content.Context
import com.shilapi.xcertplay.host.R

internal class MzdHomeView(
    context: Context, night: Boolean, close: () -> Unit, settings: () -> Unit,
    connect: () -> Unit, phoneSessionAction: () -> Unit, usb: () -> Unit,
) : MzdPageView(context, night, close, settings) {
    val connectButton = addAction(context.getString(R.string.connect_phone), click = connect)
    val phoneSessionButton = addAction(context.getString(R.string.choose_iphone), false, phoneSessionAction)
    init {
        instructions.text = context.getString(R.string.mazda_home_instructions)
    }
    val usbButton = addHeaderAction(context.getString(R.string.mazda_usb_connection), usb)
}
