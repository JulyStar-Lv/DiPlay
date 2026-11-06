package com.shilapi.vehicle.mzd

import android.os.IBinder
import android.os.Parcel

/** Read the OEM HUD type; unavailable configuration falls back to display name and geometry. */
internal fun readMzdHudType(): Int? = readMzdHudTypeFrom(runCatching {
    Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        .invoke(null, "tinnove_config") as? IBinder
}.getOrNull())

internal fun readMzdHudTypeFrom(remote: IBinder?): Int? = runCatching {
    val descriptor = "com.tinnove.config.aidl.IConfigInterface"
    if (remote == null || !remote.isBinderAlive || remote.interfaceDescriptor != descriptor) return null
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(descriptor)
        data.writeString("car_feature_hud_type")
        check(remote.transact(3, data, reply, 0)) // Verified getValue(String) ABI.
        reply.readException()
        reply.readString()?.toIntOrNull()
    } finally { reply.recycle(); data.recycle() }
}.getOrNull()
