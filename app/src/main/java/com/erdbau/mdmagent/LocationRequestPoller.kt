package com.erdbau.mdmagent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * "Chiedi posizione adesso" dalla pagina di gestione: la pagina scrive
 * {id: ora richiesta} in richieste/posizione.json; il tablet controlla quel
 * file e, se c'è una richiesta nuova per lui, manda subito la posizione
 * (StatusReporter.reportLocation -> posizioni/<id>.json).
 *
 * Controlli: ogni POLL_AWAKE_MS mentre il kiosk è acceso (MainActivity) e con
 * una sveglia "allow while idle" ogni POLL_IDLE_MIN minuti per lo standby.
 * Richiesta condizionale con ETag: la risposta 304 ("niente di nuovo") non
 * conta nei limiti dell'API GitHub. Solo se il profilo ha inviaPosizione.
 */
internal object LocationRequestPoller {
    private const val TAG = "LocationRequest"
    private const val URL_REQ =
        "https://api.github.com/repos/erdbau-projects/mdmagent/contents/richieste/posizione.json?ref=main"
    private const val PREFS = "location_requests"
    private const val KEY_LAST = "lastAnswered"
    const val POLL_AWAKE_MS = 90_000L
    private const val POLL_IDLE_MIN = 10L
    const val ACTION_POLL = "com.erdbau.mdmagent.POLL_LOCATION_REQUEST"

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var etag: String? = null

    fun enabled(context: Context): Boolean =
        ProfileManager.cachedSettings(context)?.optBoolean("inviaPosizione") == true &&
            BuildConfig.GITHUB_STATUS_TOKEN.isNotBlank()

    fun pollAsync(context: Context) {
        val ctx = context.applicationContext
        if (!enabled(ctx)) return
        executor.execute {
            try { poll(ctx) } catch (e: Exception) { Log.w(TAG, "Controllo richieste fallito: ${e.message}") }
        }
    }

    private fun poll(ctx: Context) {
        val c = URL(URL_REQ).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer ${BuildConfig.GITHUB_STATUS_TOKEN}")
        c.setRequestProperty("Accept", "application/vnd.github.raw")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        etag?.let { c.setRequestProperty("If-None-Match", it) }
        try {
            when (c.responseCode) {
                304 -> return
                404 -> { etag = null; return }
                200 -> {
                    etag = c.getHeaderField("ETag")
                    val json = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    val req = json.optString(StatusReporter.deviceId(ctx))
                    val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (req.isNotEmpty() && req > prefs.getString(KEY_LAST, "")!!) {
                        prefs.edit().putString(KEY_LAST, req).apply()
                        Log.i(TAG, "Richiesta di posizione delle $req: invio")
                        StatusReporter.reportLocation(ctx)
                    }
                }
                else -> Log.w(TAG, "Controllo richieste: HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun pending(ctx: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, LocationAlarmReceiver::class.java).setAction(ACTION_POLL),
        flags or PendingIntent.FLAG_IMMUTABLE)

    /** Sveglia per lo standby: attiva solo se il profilo invia la posizione. */
    fun ensureIdleAlarm(context: Context, force: Boolean = false) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!enabled(ctx)) {
            pending(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            return
        }
        if (!force && pending(ctx, PendingIntent.FLAG_NO_CREATE) != null) return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + POLL_IDLE_MIN * 60_000L, pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT)!!)
    }
}
