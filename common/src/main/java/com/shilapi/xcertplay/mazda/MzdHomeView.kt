package com.shilapi.xcertplay.mazda

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.host.R

internal class MzdHomeView(
    context: Context, night: Boolean, close: () -> Unit, settings: () -> Unit,
    connect: () -> Unit, private val choosePhone: () -> Unit, usb: () -> Unit,
    private val sessionChanged: () -> Unit = {},
) : MzdPageView(context, night, close, settings) {
    val connectButton = addAction(context.getString(R.string.connect_phone), click = connect)
    val phoneSessionButton = addAction(context.getString(R.string.choose_iphone), false, ::onPhoneSessionClick)
    init {
        instructions.text = context.getString(R.string.mazda_home_instructions)
    }
    val usbButton = addHeaderAction(context.getString(R.string.mazda_usb_connection), usb)

    fun refreshSession() {
        phoneSessionButton.text = context.getString(
            if (CarPlayBackgroundSession.hasSession()) R.string.disconnect else R.string.choose_iphone)
        phoneSessionButton.isEnabled = !CarPlayBackgroundSession.isStopping()
    }

    private fun onPhoneSessionClick() {
        // The status tick can be stale. Decide from the live session at click time.
        if (CarPlayBackgroundSession.isStopping()) return
        if (!CarPlayBackgroundSession.hasSession()) {
            choosePhone()
            return
        }
        phoneSessionButton.isEnabled = false
        CarPlayBackgroundSession.stop {
            Handler(Looper.getMainLooper()).post {
                refreshSession()
                sessionChanged()
            }
        }
        refreshSession()
    }
}
