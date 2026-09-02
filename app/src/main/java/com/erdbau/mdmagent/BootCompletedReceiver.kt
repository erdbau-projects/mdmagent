package com.erdbau.mdmagent

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Riceve ACTION_BOOT_COMPLETED e, se questa app è ancora Device Owner,
 * rilancia MainActivity per far rientrare il tablet in Lock Task Mode
 * (kiosk) dopo il riavvio, senza intervento manuale.
 *
 * Nota: BOOT_COMPLETED è tra le poche broadcast implicite che il sistema
 * consegna anche ad app in stato "stopped" (mai avviate dopo l'installazione),
 * proprio per casi come questo (device management). Non serve altro oltre
 * al permesso RECEIVE_BOOT_COMPLETED in manifest.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Boot completato ma l'app non è (più) Device Owner: nulla da fare")
            return
        }

        Log.i(TAG, "Boot completato, rilancio MainActivity per rientrare in kiosk mode")
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
