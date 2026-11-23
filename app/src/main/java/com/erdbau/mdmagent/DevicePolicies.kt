package com.erdbau.mdmagent

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Impostazioni del reparto (dal profilo firmato, vedi ProfileManager):
 *
 *   "impostazioni": {
 *     "posizione": true,             posizione accesa e non modificabile
 *     "bluetooth": true,             Bluetooth acceso (si può ancora abbinare)
 *     "oraAutomatica": true,         data/ora/fuso automatici e non modificabili
 *     "schermoAccesoInCarica": true, schermo sempre acceso quando è in carica
 *     "bloccaReset": true,           niente ripristino di fabbrica né modalità provvisoria
 *     "volumeMinimo": 50,            volume (%) sotto cui non si scende; 0 = libero
 *     "bloccaHotspotRete": true      niente hotspot né modifiche alla rete mobile
 *   }
 *
 * e permessi delle singole app ("permessi": ["posizione", "fotocamera", ...]
 * dentro ogni app del profilo), concessi in automatico dal Device Owner.
 *
 * Tutto si applica entrando in kiosk e ogni 30 minuti; in manutenzione i
 * blocchi si tolgono (si può resettare, cambiare data, ecc.) e tornano al
 * rientro in kiosk. Ogni voce è indipendente: se una fallisce le altre
 * restano applicate.
 */
object DevicePolicies {
    private const val TAG = "DevicePolicies"
    private const val PREFS = "politiche"
    private const val KEY_GRANTED = "permessiConcessi"

    /** Nome semplice del profilo -> permessi Android (solo quelli esistenti sulla versione del tablet). */
    fun androidPermissions(name: String): List<String> = when (name) {
        "posizione" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        "posizioneSempre" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
            (if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList())
        "fotocamera" -> listOf(Manifest.permission.CAMERA)
        "microfono" -> listOf(Manifest.permission.RECORD_AUDIO)
        "notifiche" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "bluetooth" -> if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyList()
        "foto" -> if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        "telefono" -> listOf(Manifest.permission.CALL_PHONE)
        else -> emptyList()
    }

    // Blocchi gestiti dalle impostazioni (tolti in manutenzione).
    private val LOCATION_R = listOf(UserManager.DISALLOW_CONFIG_LOCATION)
    private val TIME_R = listOf(UserManager.DISALLOW_CONFIG_DATE_TIME)
    private val RESET_R = listOf(UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_SAFE_BOOT)
    private val NET_R = listOf(UserManager.DISALLOW_CONFIG_TETHERING, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
    private val ALL_R = LOCATION_R + TIME_R + RESET_R + NET_R

    internal fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName,
              apps: List<LauncherApp>, settings: JSONObject?) {
        val s = settings ?: JSONObject()
        val done = mutableListOf<String>()
        step("posizione") {
            val on = s.optBoolean("posizione")
            if (on && Build.VERSION.SDK_INT >= 30) dpm.setLocationEnabled(admin, true)
            restrictions(dpm, admin, LOCATION_R, on)
            if (on) done += "posizione"
        }
        step("ora automatica") {
            val on = s.optBoolean("oraAutomatica")
            if (on) {
                if (Build.VERSION.SDK_INT >= 30) {
                    dpm.setAutoTimeEnabled(admin, true)
                    dpm.setAutoTimeZoneEnabled(admin, true)
                } else {
                    dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "1")
                    dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "1")
                }
                done += "ora"
            }
            restrictions(dpm, admin, TIME_R, on)
        }
        step("schermo acceso in carica") {
            val on = s.optBoolean("schermoAccesoInCarica")
            // 7 = alimentatore | USB | wireless
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (on) "7" else "0")
            if (on) done += "schermo"
        }
        step("blocco reset") {
            val on = s.optBoolean("bloccaReset")
            restrictions(dpm, admin, RESET_R, on)
            if (on) done += "reset"
        }
        step("hotspot e rete mobile") {
            val on = s.optBoolean("bloccaHotspotRete")
            restrictions(dpm, admin, NET_R, on)
            if (on) done += "rete"
        }
        step("bluetooth") {
            if (s.optBoolean("bluetooth") && enableBluetooth(context, dpm, admin)) done += "bluetooth"
        }
        step("volume") {
            val min = s.optInt("volumeMinimo", 0).coerceIn(0, 100)
            if (min > 0) {
                dpm.setMasterVolumeMuted(admin, false)
                raiseVolume(context, min)
                done += "volume>=$min%"
            }
        }
        step("permessi app") { done += "permessi:" + grantPermissions(context, dpm, admin, apps) }
        Log.i(TAG, "Impostazioni del reparto applicate: ${done.joinToString(", ")}")
    }

    /** Manutenzione: via i blocchi (reset, data, posizione, rete), tornano al rientro in kiosk. */
    fun relaxForMaintenance(dpm: DevicePolicyManager, admin: ComponentName) {
        step("sblocco manutenzione") { restrictions(dpm, admin, ALL_R, false) }
    }

    /** Stato da riportare nel check-in, per vedere nella pagina se le impostazioni funzionano. */
    fun state(context: Context): JSONObject = JSONObject().apply {
        try {
            val lm = context.getSystemService(LocationManager::class.java)
            put("posizione", if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else JSONObject.NULL)
        } catch (e: Exception) { put("posizione", JSONObject.NULL) }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            put("bluetooth", adapter?.isEnabled ?: JSONObject.NULL)
        } catch (e: Exception) { put("bluetooth", JSONObject.NULL) }
        try {
            put("schermoAccesoInCarica",
                Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0)
        } catch (e: Exception) { put("schermoAccesoInCarica", JSONObject.NULL) }
    }

    private fun restrictions(dpm: DevicePolicyManager, admin: ComponentName, keys: List<String>, on: Boolean) {
        for (k in keys) if (on) dpm.addUserRestriction(admin, k) else dpm.clearUserRestriction(admin, k)
    }

    @SuppressLint("MissingPermission")
    private fun enableBluetooth(context: Context, dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            // Il Device Owner si concede da solo il permesso runtime necessario.
            dpm.setPermissionGrantState(admin, context.packageName, Manifest.permission.BLUETOOTH_CONNECT,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (adapter.isEnabled) return true
        // Da Android 13 enable() è vietato alle app, ma non al Device Owner.
        @Suppress("DEPRECATION")
        val ok = adapter.enable()
        if (!ok) Log.w(TAG, "Bluetooth: enable() rifiutato dal sistema")
        return ok
    }

    private fun raiseVolume(context: Context, minPercent: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        for (stream in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_RING,
                                  AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM)) {
            try {
                val max = am.getStreamMaxVolume(stream)
                val floor = (max * minPercent + 99) / 100
                if (am.getStreamVolume(stream) < floor) am.setStreamVolume(stream, floor, 0)
            } catch (e: SecurityException) {
                // es. "Non disturbare" attivo: non si forza, solo log.
                Log.w(TAG, "Volume stream $stream non modificabile: ${e.message}")
            }
        }
    }

    /**
     * Concede i permessi del profilo alle app installate e riporta a "default"
     * (non più forzati) quelli tolti dal profilo. Ritorna quanti ne ha concessi.
     */
    private fun grantPermissions(context: Context, dpm: DevicePolicyManager, admin: ComponentName,
                                 apps: List<LauncherApp>): Int {
        val wanted = mutableMapOf<String, MutableSet<String>>()
        for (a in apps) for (p in a.permissions) wanted.getOrPut(a.packageName) { mutableSetOf() } += androidPermissions(p)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = JSONObject(prefs.getString(KEY_GRANTED, "{}") ?: "{}")
        var granted = 0
        val now = JSONObject()
        for ((pkg, perms) in wanted) {
            if (!installed(context, pkg)) continue
            val ok = org.json.JSONArray()
            for (perm in perms) {
                val res = try {
                    dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                } catch (e: Exception) { false }
                if (res) { granted++; ok.put(perm) } else Log.w(TAG, "Permesso $perm non concesso a $pkg (non dichiarato dall'app?)")
            }
            now.put(pkg, ok)
        }
        // Permessi forzati in passato ma non più nel profilo: tornano liberi.
        for (pkg in before.keys()) {
            val old = before.optJSONArray(pkg) ?: continue
            val keep = now.optJSONArray(pkg)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            for (i in 0 until old.length()) {
                val perm = old.getString(i)
                if (perm !in keep && installed(context, pkg)) {
                    try {
                        dpm.setPermissionGrantState(admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
                    } catch (e: Exception) { }
                }
            }
        }
        prefs.edit().putString(KEY_GRANTED, now.toString()).apply()
        return granted
    }

    private fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Impostazione '$name' non applicata: ${e.message}")
        }
    }
}
