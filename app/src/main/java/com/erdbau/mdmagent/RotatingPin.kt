package com.erdbau.mdmagent

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PIN di manutenzione mensile a rotazione.
 *
 * PIN del mese = HMAC-SHA256(segreto, "AAAA-MM"), primi 4 byte come intero
 * positivo, modulo 1.000.000, 6 cifre. Il segreto sta in app/kiosk.properties
 * (non versionato): conoscere la regola non basta per ricavare il PIN.
 * Cambia alla mezzanotte del primo del mese, ora di Roma.
 *
 * Lo stesso calcolo è nel portale camweb (pagina /pin), che mostra il PIN
 * del mese a chi è abilitato: i due devono restare identici.
 *
 * Nei primi GRACE_DAYS giorni del mese vale anche il PIN del mese prima,
 * così un orologio del tablet un po' indietro non chiude fuori nessuno.
 * Il PIN di emergenza fisso (ADMIN_EXIT_PIN) resta sempre valido a parte.
 */
object RotatingPin {
    private val ROME: TimeZone = TimeZone.getTimeZone("Europe/Rome")
    private const val GRACE_DAYS = 3

    fun forMonth(secret: String, year: Int, month: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val msg = String.format(Locale.US, "%04d-%02d", year, month)
        val d = mac.doFinal(msg.toByteArray(Charsets.US_ASCII))
        val n = ((d[0].toInt() and 0x7f) shl 24) or
            ((d[1].toInt() and 0xff) shl 16) or
            ((d[2].toInt() and 0xff) shl 8) or
            (d[3].toInt() and 0xff)
        return String.format(Locale.US, "%06d", n % 1_000_000)
    }

    /** PIN accettati in questo momento (mese corrente + eventuale tolleranza). */
    fun accepted(secret: String, nowMillis: Long = System.currentTimeMillis()): Set<String> {
        if (secret.isEmpty()) return emptySet()
        val cal = Calendar.getInstance(ROME).apply { timeInMillis = nowMillis }
        val out = mutableSetOf(forMonth(secret, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1))
        if (cal.get(Calendar.DAY_OF_MONTH) <= GRACE_DAYS) {
            cal.add(Calendar.MONTH, -1)
            out += forMonth(secret, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }
        return out
    }

    fun isValid(pin: String, secret: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        pin.length == 6 && pin in accepted(secret, nowMillis)
}
