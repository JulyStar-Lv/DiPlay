package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.mazda.MzdIntegration
import com.shilapi.xcertplay.host.R

/** Keeps the automatic-launch timer alive after the boot/Bluetooth receiver returns. */
class AutoStartDelayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var bootRequested = false
    private var phoneAddress: String? = null
    private var scheduledAt = 0L
    private var scheduled = false
    private val open = Runnable { openDiPlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        bootRequested = bootRequested || intent.getBooleanExtra(EXTRA_BOOT, false)
        intent.getStringExtra(EXTRA_PHONE)?.let { phoneAddress = it }
        if (scheduled) {
            Log.i(TAG, "Automatic launch merged; keeping the original ${DELAY_MILLIS}ms deadline")
            return START_NOT_STICKY
        }
        try {
            val notification = if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
            }.setSmallIcon(R.drawable.ic_diplay_notification)
                .setContentTitle(getString(R.string.app_name)).setOngoing(true).build()
            // The UI is not visible yet: do not request microphone/location service types here.
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(NOTIFICATION_ID, notification)
            scheduled = true
            scheduledAt = SystemClock.elapsedRealtime()
            handler.postDelayed(open, DELAY_MILLIS)
            Log.i(TAG, "Automatic launch scheduled delayMs=$DELAY_MILLIS")
        } catch (error: RuntimeException) {
            failed(error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun openDiPlay() {
        val boot = bootRequested && AirPlayPersistence.loadAutoStartOnBoot(this)
        val phone = phoneAddress?.let {
            DiPlayPreferences.connectOnPhoneBluetooth(this) &&
                it.equals(DiPlayPreferences.phoneAddress(this), ignoreCase = true) &&
                !CarPlayBackgroundSession.hasSession() &&
                (Build.VERSION.SDK_INT < 31 ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        } == true
        if (bootRequested && !boot) StartupDiagnosticSnapshot.launchCancelled(this)
        if (phoneAddress != null && !phone) PhoneWakeDiagnostics.record(this, "cancelled")
        try {
            if (!boot && !phone) return
            val launch = Intent(this, DiPlayActivity::class.java).apply {
                if (boot) {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                if (phone) putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, PhoneBluetoothReceiver.launchToken)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            MzdIntegration.routeLaunch(this, launch)
            Log.i(TAG, "Automatic launch dispatched elapsedMs=${SystemClock.elapsedRealtime() - scheduledAt} boot=$boot bluetooth=$phone")
            startActivity(launch)
            if (boot) StartupDiagnosticSnapshot.launchResult(this)
            if (phone) PhoneWakeDiagnostics.record(this, "startActivity-returned")
        } catch (error: RuntimeException) {
            failed(error)
        } finally {
            stopSelf()
        }
    }

    private fun failed(error: RuntimeException) {
        if (bootRequested) StartupDiagnosticSnapshot.launchResult(this, error)
        if (phoneAddress != null) PhoneWakeDiagnostics.record(this, "failed", error)
        Log.w(TAG, "Automatic launch failed: ${error.javaClass.simpleName}")
    }

    override fun onDestroy() {
        handler.removeCallbacks(open)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DiPlayAutoStart"
        private const val DELAY_MILLIS = 10_000L
        private const val CHANNEL = "diplay_connection"
        private const val NOTIFICATION_ID = 20_020
        private const val EXTRA_BOOT = "boot"
        private const val EXTRA_PHONE = "phone"

        fun requestBoot(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, AutoStartDelayService::class.java).putExtra(EXTRA_BOOT, true))
        }

        fun requestPhone(context: Context, address: String) {
            ContextCompat.startForegroundService(context,
                Intent(context, AutoStartDelayService::class.java).putExtra(EXTRA_PHONE, address))
        }
    }
}
