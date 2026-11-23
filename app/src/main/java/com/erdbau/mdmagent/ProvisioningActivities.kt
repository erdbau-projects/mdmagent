package com.erdbau.mdmagent

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log

/**
 * Provisioning da QR code (tablet resettato: 6 tocchi sulla schermata di
 * benvenuto, poi QR generato dalla pagina di gestione). Da Android 10 il
 * sistema chiede al programma di gestione due risposte durante la
 * configurazione: senza queste due activity il provisioning da QR fallisce.
 * Non hanno interfaccia: rispondono e si chiudono.
 */

/** Che tipo di gestione: sempre dispositivo aziendale completo (Device Owner). */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("Provisioning", "GET_PROVISIONING_MODE: dispositivo gestito completo")
        setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        finish()
    }
}

/**
 * Fine del provisioning, prima che la configurazione guidata termini: MDMAgent
 * è già Device Owner. Si imposta come schermata Home, così a fine
 * configurazione il tablet apre direttamente il kiosk.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = MdmDeviceAdminReceiver.getComponentName(this)
            if (dpm.isDeviceOwnerApp(packageName)) {
                val home = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, home, ComponentName(this, MainActivity::class.java))
                Log.i("Provisioning", "ADMIN_POLICY_COMPLIANCE: Device Owner, MDMAgent impostata come Home")
            }
        } catch (e: Exception) {
            Log.w("Provisioning", "Compliance: ${e.message}")
        }
        setResult(RESULT_OK)
        finish()
    }
}
