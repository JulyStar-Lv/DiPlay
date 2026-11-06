package com.shilapi.xcertplay.mazda

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.WirelessPermissions
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Preparation only. The upstream host/controller owns every running session and hotspot. */
internal class MzdConnectionFlow(
    private val activity: DiPlayActivity,
    private val retry: () -> Unit,
    private val choosePhone: () -> Unit,
    private val editManual: () -> Unit,
    private val builder: () -> AlertDialog.Builder,
) {
    private enum class Page { BLUETOOTH, WIFI, HOTSPOT, MANUAL_DETAILS, PERMISSIONS }
    private var pending: Page? = null
    private var leftSettings = false
    private var requesting = false
    private var wifiAccepted = false
    private var ready = false
    private var connecting = false
    private var editingManual = false
    private var choosingPhone = false
    private var projecting = false
    private var forceManual = false
    private var dialog: AlertDialog? = null
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        if (missingPermissions().isEmpty()) retry() else permissionHelp()
    }

    fun prepare(): Boolean {
        if (!MzdIntegration.enabled(activity)) return true
        connecting = true
        if (AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.WIFI_P2P) {
            AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        }
        if (projecting) return false
        if (ready) return true
        if (requesting || editingManual || choosingPhone || pending != null || dialog?.isShowing == true) return false
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            requesting = true
            permissions.launch(missing.toTypedArray())
            return false
        }
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { selectPhone(); return false }
        if (!adapter.isEnabled) {
            show(builder().setTitle(R.string.turn_on_bluetooth)
                .setMessage(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first)
                .setPositiveButton(R.string.open_bluetooth) { _, _ -> go { openBluetoothSettings() } }
                .setNegativeButton(R.string.cancel) { _, _ -> cancel() })
            return false
        }
        val address = DiPlayPreferences.phoneAddress(activity)
        if (adapter.bondedDevices.none { it.address == address }) { selectPhone(); return false }

        val mode = AirPlayPersistence.loadWirelessHotspotMode(activity)
        if (mode != WirelessHotspotMode.EXISTING_WIFI) {
            val ssid = AirPlayPersistence.loadManualHotspotSsid(activity)
            val password = AirPlayPersistence.loadManualHotspotPassphrase(activity)
            if ((forceManual || MzdWirelessConnection.hasManualDetails(activity)) &&
                ManualHotspotValidation.error(ssid, password) != null) {
                editDetails()
                return false
            }
            if (MzdWirelessConnection.hasManualDetails(activity) && CarHotspotStatus.isEnabled(activity) == false) {
                show(builder().setTitle(R.string.car_hotspot_is_off)
                    .setMessage(activity.getString(R.string.msg_car_hotspot_connect, ssid))
                    .setPositiveButton(R.string.open_car_settings) { _, _ -> go { openNetwork(Page.HOTSPOT) } }
                    .setNegativeButton(R.string.cancel) { _, _ -> cancel() })
                return false
            }
            if (!wifiAccepted && MzdWirelessConnection.wifiWarning(activity, mode) != null) {
                showWifiReminder()
                return false
            }
        }
        ready = true
        return true
    }

    fun openBluetoothSettings(): Boolean {
        if (!MzdIntegration.enabled(activity)) return false
        val page = Page.BLUETOOTH.takeIf { connecting }
        return openOem("INTENT_TO_BLUETOOTH", page) || open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), page)
    }

    fun openNetworkSettings(): Boolean {
        if (!MzdIntegration.enabled(activity)) return false
        return openOem("INTENT_TO_NETWORK", null)
    }

    fun onPause() { if (pending != null) leftSettings = true }

    fun onResume() {
        if (!MzdIntegration.enabled(activity)) return
        projecting = false
        if (activity.intent.getBooleanExtra(MzdWirelessConnection.MANUAL_FALLBACK, false)) {
            activity.intent.removeExtra(MzdWirelessConnection.MANUAL_FALLBACK)
            cancel()
            forceManual = true
            show(builder().setTitle(R.string.mazda_local_hotspot_failed_title)
                .setMessage(R.string.mazda_local_hotspot_failed_message)
                .setPositiveButton(R.string.open_car_hotspot_settings) { _, _ -> go { openNetwork(Page.MANUAL_DETAILS) } }
                .setNegativeButton(R.string.cancel) { _, _ -> cancel() })
            return
        }
        val page = pending?.takeIf { leftSettings } ?: return
        pending = null
        leftSettings = false
        when (page) {
            Page.MANUAL_DETAILS -> editDetails()
            Page.BLUETOOTH -> if (missingPermissions().isEmpty() &&
                activity.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) {
                if (choosingPhone) selectPhone() else retry()
            }
                else {
                    choosingPhone = false
                    toast(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first)
                }
            Page.HOTSPOT -> if (CarHotspotStatus.isEnabled(activity) != false) retry()
                else toast(R.string.connection_help_car_hotspot_off)
            Page.WIFI -> if (MzdWirelessConnection.wifiWarning(activity,
                AirPlayPersistence.loadWirelessHotspotMode(activity)) == null) retry() else showWifiReminder()
            Page.PERMISSIONS -> if (missingPermissions().isEmpty()) retry()
                else toast(WirelessPermissions.refusalMessage(missingPermissions()))
        }
    }

    fun saveState(out: Bundle) {
        out.putString("mazda_connection_page", pending?.name)
        out.putBoolean("mazda_connection_manual", forceManual)
        out.putBoolean("mazda_connection_wifi_accepted", wifiAccepted)
    }

    fun restoreState(state: Bundle?) {
        pending = state?.getString("mazda_connection_page")?.let { value -> Page.entries.firstOrNull { it.name == value } }
        leftSettings = pending != null
        forceManual = state?.getBoolean("mazda_connection_manual") == true
        wifiAccepted = state?.getBoolean("mazda_connection_wifi_accepted") == true
    }

    fun cancel() {
        connecting = false
        pending = null
        ready = false
        wifiAccepted = false
        forceManual = false
        editingManual = false
        choosingPhone = false
        dialog?.dismiss()
        dialog = null
    }

    fun manualDetailsSaved() { editingManual = false }
    fun phoneChosen() { choosingPhone = false }
    private fun editDetails() { editingManual = true; editManual() }
    private fun selectPhone() { choosingPhone = true; choosePhone() }

    fun projectionOpened() {
        cancel()
        projecting = true
    }

    private fun missingPermissions() = WirelessPermissions.required(
        AirPlayPersistence.loadWirelessHotspotMode(activity), android.os.Build.VERSION.SDK_INT,
    ).filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun permissionHelp() {
        show(builder().setTitle(R.string.nearby_devices)
            .setMessage(WirelessPermissions.refusalMessage(missingPermissions()))
            .setPositiveButton(R.string.review_app_permissions) { _, _ -> go {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")), Page.PERMISSIONS)
            } }
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() })
    }

    private fun showWifiReminder() {
        show(builder().setTitle(R.string.mazda_wifi_off_title).setMessage(R.string.mazda_wifi_off_message)
            .setPositiveButton(R.string.open_car_wi_fi_settings) { _, _ -> go { openNetwork(Page.WIFI) } }
            .setNeutralButton(R.string.mazda_connect_anyway) { _, _ -> go { wifiAccepted = true; retry() } }
            .setNegativeButton(R.string.cancel) { _, _ -> cancel() })
    }

    private fun openNetwork(page: Page) {
        if (!openOem("INTENT_TO_NETWORK", page)) open(Intent(Settings.ACTION_WIRELESS_SETTINGS), page)
    }

    private fun openOem(target: String, page: Page?): Boolean = open(oemIntent(activity.packageName, target), page)

    private fun open(intent: Intent, page: Page?): Boolean {
        val launched = runCatching { activity.startActivity(intent) }.isSuccess
        if (launched) { pending = page; leftSettings = false }
        else toast(R.string.open_this_setting_from_your_car_s_settings_app)
        return launched
    }

    private fun go(next: () -> Unit) { dialog?.dismiss(); dialog = null; next() }
    private fun show(value: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = value.setOnCancelListener { cancel() }.show()
    }
    private fun toast(message: Int) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }

    companion object {
        fun oemIntent(caller: String, target: String): Intent = Intent().setClassName(
            "com.wt.vehiclecenter", "com.wt.vehiclecenter.activities.VehicleCenterActivity",
        ).putExtra("INTENT_TO_VEHICLE_CENTER", arrayOf(target, caller))
            // Without this marker, the OEM back button navigates Home instead of finishing settings.
            .putExtra("start_by_app", caller)
    }
}
