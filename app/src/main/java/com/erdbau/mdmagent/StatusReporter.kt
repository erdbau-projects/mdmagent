package com.erdbau.mdmagent

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Riporta periodicamente lo stato di questo tablet (seriale, versione del
 * DPC, quali app della flotta risultano installate) in un file dedicato del
 * repo GitHub (status/<seriale>.json) — così non serve più affidarsi alla
 * memoria/alla lista Play per sapere chi ha cosa: basta guardare il repo.
 *
 * ATTENZIONE SICUREZZA (scelta consapevole, non una svista): il repo è
 * pubblico (vedi SelfUpdater) e questa classe ci scrive dentro, quindi porta
 * con sé un token GitHub con permesso di scrittura — imbustato nell'APK, e
 * come ogni segreto nell'APK è recuperabile decompilando (stessa nota già
 * fatta per ADMIN_EXIT_PIN in MainActivity). Chiunque lo estraesse da UN
 * tablet potrebbe scrivere ovunque nel repo, compreso latest.json che
 * SelfUpdater scarica e installa come Device Owner su tutta la flotta: in
 * pratica, compromissione di un tablet -> rischio di compromissione di tutti.
 * Mitigazione minima adottata: il token va creato come "fine-grained PAT"
 * limitato A QUESTO SOLO REPO con permesso "Contents: Read and write" (mai
 * un classic PAT con scope repo su tutto l'account) — vedi
 * app/github_status.properties.example. Non elimina il rischio sopra
 * descritto, lo riduce solo al singolo repo invece che all'intero account.
 */
object StatusReporter {

    private const val TAG = "StatusReporter"

    private const val REPO = "erdbau-projects/mdmagent"
    private const val API_BASE = "https://api.github.com/repos/$REPO/contents/status"

    fun reportStatus(context: Context) {
        val appContext = context.applicationContext
        if (BuildConfig.GITHUB_STATUS_TOKEN.isBlank()) {
            // Build senza app/github_status.properties (es. macchina di un
            // altro sviluppatore, o build debug): salta silenziosamente,
            // stesso spirito di keystore.properties assente per la firma.
            Log.i(TAG, "Nessun token di status configurato, salto il check-in")
            return
        }
        thread(name = "StatusReporter") {
            try {
                val serial = getSerial(appContext)
                val body = buildStatusJson(appContext, serial)
                publish(serial, body)
                Log.i(TAG, "Check-in di stato inviato per $serial")
            } catch (e: Exception) {
                // Come SelfUpdater: un fallimento qui (rete assente, API
                // GitHub irraggiungibile, ecc.) non deve mai bloccare il
                // kiosk, solo un log.
                Log.w(TAG, "Check-in di stato fallito (non bloccante): ${e.message}")
            }
        }
    }

    /**
     * Build.getSerial() richiederebbe normalmente il permesso runtime
     * READ_PHONE_STATE, ma le app Device Owner ne sono esentate dal sistema
     * (accesso concesso senza dialog) — coerente col fatto che questa classe
     * va invocata solo a Device Owner già confermato (vedi MainActivity).
     */
    private fun getSerial(context: Context): String = try {
        Build.getSerial()
    } catch (e: SecurityException) {
        Log.w(TAG, "Impossibile leggere il serial (non Device Owner?), uso ANDROID_ID come fallback", e)
        android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            ?: "sconosciuto"
    }

    private fun buildStatusJson(context: Context, serial: String): JSONObject {
        val pm = context.packageManager
        val ownInfo = pm.getPackageInfo(context.packageName, 0)
        val ownVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ownInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") ownInfo.versionCode.toLong()
        }

        val apps = JSONObject()
        for (pkg in trackedPackageNames(context)) {
            val entry = JSONObject()
            try {
                val info = pm.getPackageInfo(pkg, 0)
                entry.put("installed", true)
                entry.put("versionName", info.versionName ?: "")
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                entry.put("installed", false)
            }
            apps.put(pkg, entry)
        }

        return JSONObject().apply {
            put("serial", serial)
            put("model", Build.MODEL)
            put("dpcVersionCode", ownVersionCode)
            put("dpcVersionName", ownInfo.versionName ?: "")
            put("lastCheckIn", isoTimestampNow())
            put("apps", apps)
        }
    }

    /** Unione dei package tracciati: le app installate a mano (launcher_apps.json) e quelle spinte in silenzio (managed_apps.json). */
    private fun trackedPackageNames(context: Context): List<String> {
        val names = LinkedHashSet<String>()
        for (assetName in listOf("launcher_apps.json", "managed_apps.json")) {
            try {
                val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val pkg = array.getJSONObject(i).optString("packageName")
                    if (pkg.isNotBlank() && pkg != "com.esempio.app") names.add(pkg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Impossibile leggere $assetName per la lista app da tracciare", e)
            }
        }
        return names.toList()
    }

    private fun isoTimestampNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** GET per l'eventuale sha esistente, poi PUT (create o update) — Contents API di GitHub. */
    private fun publish(serial: String, body: JSONObject) {
        val url = "$API_BASE/$serial.json"
        val existingSha = fetchExistingSha(url)

        val content = Base64.encodeToString(body.toString(2).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("message", "status: check-in $serial")
            put("content", content)
            if (existingSha != null) put("sha", existingSha)
        }

        val connection = openConnection(url, "PUT")
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IOException("PUT status/$serial.json fallito: HTTP $responseCode")
        }
        connection.disconnect()
    }

    private fun fetchExistingSha(url: String): String? {
        val connection = openConnection(url, "GET")
        return try {
            when (connection.responseCode) {
                200 -> {
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    json.optString("sha").ifBlank { null }
                }
                404 -> null // File non ancora creato per questo seriale: prima pubblicazione.
                else -> throw IOException("GET status esistente fallito: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.GITHUB_STATUS_TOKEN}")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (method == "PUT") connection.setRequestProperty("Content-Type", "application/json")
        return connection
    }
}
