package com.erdbau.mdmagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Riceve l'esito asincrono di ogni installazione avviata da
 * SilentAppInstaller tramite PackageInstaller.commit(pendingIntent).
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(SilentAppInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Installazione riuscita: $packageName")

            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // Non dovrebbe succedere per un'app Device Owner: se capita,
                // vuol dire che lo stato Device Owner è stato perso, o che
                // l'APK richiede conferme non concedibili in automatico.
                Log.w(TAG, "Richiesto intervento utente per $packageName (inatteso da Device Owner)")

            else ->
                Log.e(TAG, "Installazione fallita per $packageName (status=$status): $message")
        }
    }

    companion object {
        private const val TAG = "InstallStatusReceiver"
    }
}
