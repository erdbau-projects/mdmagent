package com.erdbau.mdmagent

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dati della SIM aziendale e seriale vero del tablet, per la lista nella pagina
 * di gestione. Il Device Owner si concede da solo i permessi "telefono"
 * (READ_PHONE_STATE / READ_PHONE_NUMBERS). Attenzione: molte SIM non hanno il
 * numero scritto dentro (dipende dall'operatore) -> "numero" può mancare;
 * l'ICCID (stampato sulla SIM) c'è sempre e permette di riconoscerla.
 */
internal object SimInfo {
    private const val TAG = "SimInfo"

    private fun grantPhonePermissions(context: Context) {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = MdmDeviceAdminReceiver.getComponentName(context)
            for (perm in listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS)) {
                dpm.setPermissionGrantState(admin, context.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Permessi telefono non concessi: ${e.message}")
        }
    }

    /** Seriale di fabbrica (quello dell'etichetta), oppure null. */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun hardwareSerial(context: Context): String? {
        grantPhonePermissions(context)
        return try {
            Build.getSerial().takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        } catch (e: Exception) {
            null
        }
    }

    /** Elenco SIM attive: [{slot, numero?, operatore, iccid?}] (vuoto senza SIM). */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun sims(context: Context): JSONArray {
        grantPhonePermissions(context)
        val out = JSONArray()
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return out
            for (info in sm.activeSubscriptionInfoList.orEmpty()) {
                val number = try {
                    if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId)
                    else @Suppress("DEPRECATION") info.number
                } catch (e: Exception) { null }
                out.put(JSONObject().apply {
                    put("slot", info.simSlotIndex + 1)
                    number?.takeIf { it.isNotBlank() }?.let { put("numero", it) }
                    put("operatore", (info.carrierName?.toString() ?: "").take(40))
                    info.iccId?.takeIf { it.isNotBlank() }?.let { put("iccid", it) }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "SIM non leggibile: ${e.message}")
        }
        return out
    }
}
