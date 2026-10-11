package com.shilapi.xcertplay.mazda

import android.app.Application

/** Registered only by the Mazda application, before any Activity or service reads settings. */
class MzdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MzdDefaults.initialize(this)
    }
}
