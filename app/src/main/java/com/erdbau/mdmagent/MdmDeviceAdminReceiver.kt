package com.erdbau.mdmagent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver che gestisce il ciclo di vita del Device Admin.
 *
 * Registrato in AndroidManifest.xml con:
 *  - android:permission="android.permission.BIND_DEVICE_ADMIN"
 *  - meta-data android.app.device_admin -> res/xml/device_admin_policies.xml
 *  - intent-filter ACTION_DEVICE_ADMIN_ENABLED
 *
 * Durante il provisioning DPC (QR/NFC su dispositivo vergine), il sistema
 * imposta questa app come Device Owner e alla fine chiama
 * onProfileProvisioningComplete().
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin abilitato")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabilitato")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "Provisioning completato: app impostata come Device Owner")
            // Lancia la MainActivity, che verificherà lo stato ed entrerà
            // in Lock Task Mode.
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
        } else {
            Log.w(TAG, "onProfileProvisioningComplete chiamato ma app non è Device Owner")
        }
    }

    companion object {
        private const val TAG = "MdmDeviceAdminReceiver"

        /** ComponentName di questo receiver, usato dalle API DevicePolicyManager. */
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, MdmDeviceAdminReceiver::class.java)
    }
}
