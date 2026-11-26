package com.erdbau.mdmagent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Invio della posizione a intervalli (profilo: inviaPosizione +
 * intervalloPosizione in minuti: 15, 30, 60, 180; 360 = solo col check-in,
 * che avviene già all'avvio e ogni 6 ore).
 *
 * AlarmManager "allow while idle": funziona anche a schermo spento (Doze),
 * con qualche minuto di tolleranza decisa da Android. In carica (cabina) è
 * puntuale. Si riprogramma a ogni scatto.
 */
internal object LocationScheduler {
    private const val TAG = "LocationScheduler"
    private const val PREFS = "location_scheduler"
    private const val KEY_INTERVAL = "intervalMin"

    private fun intervalMin(context: Context): Int {
        val s = ProfileManager.cachedSettings(context) ?: return 0
        if (!s.optBoolean("inviaPosizione")) return 0
        val m = s.optInt("intervalloPosizione", 360)
        return if (m in 15..180) m else 0
    }

    private fun pending(context: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, Intent(context, LocationAlarmReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Da chiamare all'avvio e a ogni controllo del profilo. Se l'intervallo
     * non è cambiato e la sveglia c'è già, non la sposta (altrimenti il
     * controllo del profilo ogni 30 min rimanderebbe all'infinito 1h/3h).
     */
    fun ensure(context: Context, force: Boolean = false) {
        val ctx = context.applicationContext
        LocationRequestPoller.ensureIdleAlarm(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val min = intervalMin(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (min == 0) {
            pending(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            prefs.edit().remove(KEY_INTERVAL).apply()
            return
        }
        val existing = pending(ctx, PendingIntent.FLAG_NO_CREATE)
        if (!force && existing != null && prefs.getInt(KEY_INTERVAL, 0) == min) return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + min * 60_000L, pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        prefs.edit().putInt(KEY_INTERVAL, min).apply()
        Log.i(TAG, "Prossimo invio posizione tra $min minuti")
    }
}

class LocationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocationRequestPoller.ACTION_POLL) {
            // Controllo "Chiedi posizione adesso" in standby.
            LocationRequestPoller.pollAsync(context)
            LocationRequestPoller.ensureIdleAlarm(context, force = true)
            return
        }
        StatusReporter.reportLocation(context)
        LocationScheduler.ensure(context, force = true)
    }
}
