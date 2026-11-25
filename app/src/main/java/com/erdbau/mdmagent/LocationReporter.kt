package com.erdbau.mdmagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Posizione del tablet per il check-in, solo se il profilo ha l'impostazione
 * "inviaPosizione" (spenta di default: localizzare dispositivi dati ai
 * lavoratori richiede accordo sindacale/autorizzazione, art. 4 L. 300/1970).
 *
 * Il repo dei check-in è PUBBLICO: la posizione viene cifrata con RSA-OAEP
 * (SHA-256) con la chiave pubblica qui sotto. La privata è solo sulla VM
 * (~/.keystore/posizione-privata.pem), dove la pagina di gestione la decifra.
 */
object LocationReporter {
    private const val TAG = "LocationReporter"
    private const val PUBLIC_KEY_B64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA9J3JWFvSbNCsvHp1JOwznhZMQ8WtBVFNKot22N8GUgg1+TBaQyWK80dvkqhKSjBf48NmuTxY1+ovHi4rQT6AisJ2wFWmliIDaDdntBU/wfyE+lkpBTZOXt/obQbBzXDZQA6QvJzIHgDEktFUMgNSSBaKQp1huyexGab4yUj/p1BRZEj656RhUGF5l0XL6KLXhYiHA+T3IZhnuGn4Ent39SNl0gtlRv9GSjH6rHUu7HY27xBBW9nt1ctYQ72YRw8ZZNrxuep4fEatR9dBYaKFr2qi38TC8drmRpdcpuY2G9f4VbzFD3nrSfm4rldhmRBLWImt23kZCmCXGeiiy7wctwIDAQAB"
    private const val FIX_TIMEOUT_S = 20L

    /** Posizione cifrata (base64) o null se non richiesta / non disponibile. */
    fun encryptedLocation(context: Context): String? {
        if (ProfileManager.cachedSettings(context)?.optBoolean("inviaPosizione") != true) return null
        val loc = currentLocation(context) ?: return null
        val json = JSONObject().apply {
            put("lat", loc.latitude)
            put("lon", loc.longitude)
            if (loc.hasAccuracy()) put("acc", loc.accuracy.toDouble())
            put("t", iso(loc.time))
            put("p", loc.provider ?: "")
        }.toString()
        return encryptForVm(json)
    }

    /**
     * Cifra un testo breve (max ~190 byte, RSA-2048 OAEP-SHA256) con la chiave
     * pubblica: lo legge solo la VM. Usato per posizione, SIM e seriale.
     * Se la cifratura fallisce restituisce null: mai in chiaro.
     */
    internal fun encryptForVm(plain: String): String? = try {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        val c = Cipher.getInstance("RSA/ECB/OAEPPadding")
        c.init(Cipher.ENCRYPT_MODE, key,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        Base64.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Cifratura fallita: ${e.message}")
        null
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permesso posizione non concesso")
            return null
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        // Un fix fresco (fino a 20 s), altrimenti l'ultima posizione nota più recente.
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider != null) {
                val latch = CountDownLatch(1)
                var fix: Location? = null
                val exec = Executors.newSingleThreadExecutor()
                val cancel = CancellationSignal()
                try {
                    lm.getCurrentLocation(provider, cancel, exec) { l -> fix = l; latch.countDown() }
                    latch.await(FIX_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "getCurrentLocation fallito: ${e.message}")
                } finally {
                    cancel.cancel(); exec.shutdown()
                }
                fix?.let { return it }
            }
        }
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
