package com.erdbau.mdmagent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Auto-aggiornamento del DPC stesso. A differenza di SilentAppInstaller (che
 * legge un elenco bundlato in assets/), qui il puntatore alla versione più
 * recente vive FUORI dall'APK, in un piccolo manifest JSON hostato in modo
 * stabile (es. un file nel branch principale del repo GitHub, non una
 * Release — le Release sono immutabili/versionate, qui serve invece un URL
 * sempre uguale che ad ogni release aggiorniamo per puntare alla nuova).
 *
 * Questo evita il problema circolare di bundlare "dove sarà la prossima
 * versione" dentro l'APK corrente: qualunque versione sia installata su un
 * tablet, controlla sempre lo stesso URL, e trova lì l'ultima disponibile.
 *
 * Formato atteso di UPDATE_MANIFEST_URL (JSON):
 *   {
 *     "versionCode": 2,
 *     "downloadUrl": "https://github.com/<utente>/<repo>/releases/download/vX.Y.Z/app-release.apk",
 *     "sha256": "<checksum base64 url-safe>"
 *   }
 *
 * checkAndUpdate() viene chiamato da MainActivity sia all'avvio (onCreate,
 * quindi ad ogni boot) sia periodicamente mentre il kiosk resta acceso (vedi
 * PERIODIC_CHECK_INTERVAL_MS): senza il ricontrollo periodico, un tablet
 * lasciato acceso e mai riavviato non vedrebbe mai un nuovo aggiornamento.
 */
object SelfUpdater {

    private const val TAG = "SelfUpdater"

    // Repo aziendale dedicato (erdbau-projects), separato dall'account
    // personale usato in precedenza. Se cambia di nuovo nome/proprietario,
    // aggiornare qui.
    private const val UPDATE_MANIFEST_URL =
        "https://raw.githubusercontent.com/erdbau-projects/mdmagent/main/latest.json"

    private const val MAX_RETRIES = 3
    private val RETRY_DELAYS_MS = longArrayOf(10_000, 30_000)

    /**
     * Intervallo del ricontrollo periodico (vedi MainActivity), effettuato
     * mentre il kiosk resta acceso e in uso — senza questo, un tablet mai
     * riavviato non vedrebbe mai un nuovo aggiornamento, perché altrimenti
     * checkAndUpdate() viene invocato solo all'avvio (onCreate/boot).
     */
    const val PERIODIC_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 ore

    // Evita che due controlli (es. quello di avvio e quello periodico, se
    // capitano vicini) partano in sovrapposizione e scarichino/installino
    // due volte lo stesso APK in parallelo.
    private val checkInProgress = AtomicBoolean(false)

    fun checkAndUpdate(context: Context) {
        val appContext = context.applicationContext
        if (!checkInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Controllo aggiornamento DPC già in corso, salto questa richiesta")
            return
        }
        thread(name = "SelfUpdater") {
            try {
                val manifest = fetchManifest()
                val currentVersion = getOwnVersionCode(appContext)
                if (manifest.versionCode <= currentVersion) {
                    Log.i(TAG, "DPC già aggiornato (installata $currentVersion, ultima disponibile ${manifest.versionCode})")
                    return@thread
                }
                Log.i(TAG, "Aggiornamento DPC disponibile: $currentVersion -> ${manifest.versionCode}")
                updateWithRetry(appContext, manifest)
            } catch (e: Exception) {
                // Un fallimento qui (rete assente, manifest non raggiungibile, ecc.)
                // non deve mai bloccare l'avvio del kiosk: solo un log.
                Log.w(TAG, "Controllo aggiornamento DPC fallito (non bloccante): ${e.message}")
            } finally {
                checkInProgress.set(false)
            }
        }
    }

    private data class UpdateManifest(val versionCode: Long, val downloadUrl: String, val sha256: String)

    private fun fetchManifest(): UpdateManifest {
        val tmp = File.createTempFile("latest", ".json")
        try {
            DownloadUtils.downloadToFile(UPDATE_MANIFEST_URL, tmp)
            val json = JSONObject(tmp.readText())
            return UpdateManifest(
                versionCode = json.getLong("versionCode"),
                downloadUrl = json.getString("downloadUrl"),
                sha256 = json.getString("sha256"),
            )
        } finally {
            tmp.delete()
        }
    }

    private fun getOwnVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }

    private fun updateWithRetry(context: Context, manifest: UpdateManifest) {
        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                performUpdate(context, manifest)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Tentativo $attempt/$MAX_RETRIES di auto-aggiornamento fallito: ${e.message}")
                if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
            }
        }
        Log.e(TAG, "Auto-aggiornamento DPC fallito definitivamente dopo $MAX_RETRIES tentativi", lastError)
    }

    private fun performUpdate(context: Context, manifest: UpdateManifest) {
        val apkFile = File(context.cacheDir, "dpc_update.apk")
        DownloadUtils.downloadToFile(manifest.downloadUrl, apkFile)

        val actualChecksum = DownloadUtils.computeSha256Base64Url(apkFile)
        if (actualChecksum != manifest.sha256) {
            Log.e(
                TAG,
                "Checksum non corrispondente per l'update DPC: atteso ${manifest.sha256}, ottenuto $actualChecksum. Annullato."
            )
            apkFile.delete()
            return
        }

        Log.i(TAG, "Checksum ok, installazione silenziosa dell'aggiornamento DPC in corso...")
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { s ->
            apkFile.inputStream().use { input ->
                s.openWrite(context.packageName, 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    s.fsync(output)
                }
            }

            // Nessun BroadcastReceiver dedicato per l'esito: l'app si riavvia
            // da sola non appena l'update viene applicato (stessa firma,
            // stesso package — Device Owner e Lock Task sopravvivono).
            val statusIntent = Intent(SilentAppInstaller.ACTION_INSTALL_STATUS).apply {
                setPackage(context.packageName)
                putExtra(SilentAppInstaller.EXTRA_PACKAGE_NAME, context.packageName)
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
