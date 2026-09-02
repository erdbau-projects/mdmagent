package com.erdbau.mdmagent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import java.io.File
import kotlin.concurrent.thread

/**
 * Lista fissa di app aziendali da installare in modo silenzioso (senza alcun
 * prompt utente) una volta che questa app è Device Owner. La lista va
 * mantenuta in assets/managed_apps.json, non nel codice, per poterla
 * aggiornare senza toccare Kotlin.
 */
data class ManagedApp(
    val packageName: String,
    val displayName: String,
    val downloadUrl: String,
    val sha256Base64Url: String,
    val versionCode: Long,
)

object SilentAppInstaller {

    private const val TAG = "SilentAppInstaller"
    const val ACTION_INSTALL_STATUS = "com.erdbau.mdmagent.ACTION_INSTALL_STATUS"
    const val EXTRA_PACKAGE_NAME = "com.erdbau.mdmagent.extra.PACKAGE_NAME"

    private const val MAX_RETRIES = 4
    private val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 30_000)

    /**
     * Scarica ed installa (in un thread separato) ogni app elencata in
     * managed_apps.json che non sia già presente sul device. Va chiamata
     * solo dopo aver verificato dpm.isDeviceOwnerApp(...) == true: senza
     * i privilegi di Device Owner, PackageInstaller.commit() mostrerebbe
     * comunque il dialog di conferma standard all'utente.
     */
    fun installAllManagedApps(context: Context) {
        val appContext = context.applicationContext
        thread(name = "SilentAppInstaller") {
            val apps = loadManagedApps(appContext)
            if (apps.isEmpty()) {
                Log.i(TAG, "Nessuna app gestita configurata in managed_apps.json")
                return@thread
            }
            for (app in apps) {
                val installedVersion = getInstalledVersionCode(appContext, app.packageName)
                when {
                    installedVersion == null ->
                        installWithRetry(appContext, app)
                    installedVersion < app.versionCode -> {
                        Log.i(TAG, "${app.packageName}: aggiornamento disponibile ($installedVersion -> ${app.versionCode})")
                        installWithRetry(appContext, app)
                    }
                    else ->
                        Log.i(TAG, "${app.packageName} già alla versione più recente ($installedVersion), salto")
                }
            }
        }
    }

    /**
     * Prova ad installare l'app fino a MAX_RETRIES volte, con backoff, prima
     * di arrendersi. Le reti reali sui 26 tablet avranno occasionali intoppi
     * (Wi-Fi instabile, CDN che droppa una connessione, ecc.) — un singolo
     * fallimento non deve bloccare l'installazione definitivamente.
     */
    private fun installWithRetry(context: Context, app: ManagedApp) {
        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                installApp(context, app)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Tentativo $attempt/$MAX_RETRIES fallito per ${app.packageName}: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
                }
            }
        }
        Log.e(TAG, "Installazione fallita definitivamente per ${app.packageName} dopo $MAX_RETRIES tentativi", lastError)
    }

    private fun loadManagedApps(context: Context): List<ManagedApp> {
        return try {
            val json = context.assets.open("managed_apps.json").bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val packageName = obj.optString("packageName")
                val checksum = obj.optString("sha256")
                // Salta la voce di esempio/placeholder non ancora compilata.
                if (packageName.isBlank() || checksum == "PLACEHOLDER_SHA256_BASE64URL") {
                    null
                } else {
                    ManagedApp(
                        packageName = packageName,
                        displayName = obj.optString("displayName", packageName),
                        downloadUrl = obj.getString("downloadUrl"),
                        sha256Base64Url = checksum,
                        versionCode = obj.optLong("versionCode", 0L),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile leggere managed_apps.json", e)
            emptyList()
        }
    }

    /** Versione installata (versionCode), o null se l'app non è presente. */
    private fun getInstalledVersionCode(context: Context, packageName: String): Long? {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun installApp(context: Context, app: ManagedApp) {
        Log.i(TAG, "Download di ${app.displayName} (${app.packageName})...")
        val apkFile = File(context.cacheDir, "${app.packageName}.apk")
        DownloadUtils.downloadToFile(app.downloadUrl, apkFile)

        val actualChecksum = DownloadUtils.computeSha256Base64Url(apkFile)
        if (actualChecksum != app.sha256Base64Url) {
            Log.e(
                TAG,
                "Checksum non corrispondente per ${app.packageName}: atteso ${app.sha256Base64Url}, " +
                    "ottenuto $actualChecksum. Installazione annullata."
            )
            apkFile.delete()
            return
        }

        Log.i(TAG, "Checksum ok, installazione silenziosa di ${app.packageName}...")
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { s ->
            apkFile.inputStream().use { input ->
                s.openWrite(app.packageName, 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    s.fsync(output)
                }
            }

            val statusIntent = Intent(ACTION_INSTALL_STATUS).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_PACKAGE_NAME, app.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(pendingIntent.intentSender)
        }

        apkFile.delete()
    }

}
