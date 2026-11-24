package com.erdbau.mdmagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.concurrent.thread

/**
 * Profili per reparto: quali app mostra il kiosk su questo tablet.
 *
 * I profili stanno nel repo in profili.json (modificato con
 * tools/profili.py) e arrivano ai tablet come profili.signed.json:
 *   { "data": "<testo esatto di profili.json>", "sig": "<firma ECDSA base64>" }
 * La firma è fatta sulla VM con una chiave privata che NON sta nel repo né
 * nell'APK: il token GitHub dentro l'APK basta per scrivere nel repo, ma non
 * per firmare. Senza firma valida il file viene ignorato.
 *
 * Formato di profili.json:
 *   { "predefinito": "camionisti",
 *     "profili": { "camionisti": { "nome": "Camionisti",
 *                                  "apps": [ {"packageName": ..., "displayName": ...} ] } },
 *     "tablet": { "<id del tablet come in status/>": "camionisti" } }
 *
 * Il profilo valido viene salvato in locale: senza rete il tablet continua
 * col suo ultimo profilo. Se non ne ha mai ricevuto uno usa
 * assets/launcher_apps.json, come prima dei profili.
 */
object ProfileManager {
    private const val TAG = "ProfileManager"

    // Catalogo dei tablet (nome da mostrare sul kiosk). Non firmato: contiene
    // solo testo da visualizzare, nessuna regola del kiosk.
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/erdbau-projects/mdmagent/main/tools/tablet_catalogo.json"

    private const val SIGNED_URL =
        "https://raw.githubusercontent.com/erdbau-projects/mdmagent/main/profili.signed.json"

    // Chiave pubblica (EC P-256, X.509 DER) della chiave di firma dei profili.
    // La privata è solo sulla VM (~/.keystore/profili-firma.pem).
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJC1mU07mqrr6Vk9In29pmxIrPB/yQGg+zxYfTXTyXdlBbg5yB1+8CYWPBhDTzzyiQyIlqAQWxy9mdlMi86qTqw=="

    // Pacchetti che non entrano mai nel kiosk, qualunque cosa dica il
    // profilo: aprirebbero una via d'uscita (impostazioni, installazioni,
    // launcher di sistema). Difesa in più oltre alla firma.
    private val FORBIDDEN = setOf(
        "com.android.settings",
        "com.samsung.android.app.settings",
        "com.android.vending",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
    )

    private const val PREFS = "profili"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 60_000L
    private const val KEY_ID = "id"
    private const val KEY_NAME = "nome"
    private const val KEY_APPS = "apps"
    private const val KEY_SETTINGS = "impostazioni"
    private const val KEY_TABLET_NAME = "nomeTablet"

    /** App del profilo salvato, o null se il tablet non ha mai ricevuto un profilo. */
    internal fun cachedApps(context: Context): List<LauncherApp>? {
        val raw = prefs(context).getString(KEY_APPS, null) ?: return null
        return try {
            parseApps(JSONArray(raw)).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Nome del tablet dal catalogo (es. "TAB-ERD-052"), o null. */
    fun cachedTabletName(context: Context): String? = prefs(context).getString(KEY_TABLET_NAME, null)

    /** Impostazioni del reparto del profilo salvato (vedi DevicePolicies), o null. */
    fun cachedSettings(context: Context): JSONObject? =
        prefs(context).getString(KEY_SETTINGS, null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    /** Id del profilo attivo ("camionisti", ...), o null se si usa la lista integrata. */
    fun cachedProfileId(context: Context): String? = prefs(context).getString(KEY_ID, null)

    /** Nome leggibile del profilo attivo ("Camionisti", ...), o null. */
    fun cachedProfileName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    /**
     * Scarica e verifica i profili in background. Se il profilo di questo
     * tablet è cambiato lo salva e chiama onChanged sul thread principale.
     * Qualsiasi errore (rete, firma, formato) lascia tutto com'è.
     */
    fun refresh(context: Context, attempt: Int = 1, onChanged: () -> Unit) {
        val appContext = context.applicationContext
        thread(name = "ProfileManager") {
            // Nome del tablet: indipendente dal profilo (se cambia solo il nome
            // si aggiorna comunque la scritta).
            val nameChanged = refreshTabletName(appContext)
            if (nameChanged) Handler(Looper.getMainLooper()).post(onChanged)
            try {
                val signed = JSONObject(download("$SIGNED_URL?t=${System.currentTimeMillis() / 60000}"))
                val data = signed.getString("data")
                if (!verify(data, signed.getString("sig"))) {
                    Log.w(TAG, "Firma dei profili NON valida: ignorati")
                    return@thread
                }
                val root = JSONObject(data)
                val deviceId = StatusReporter.deviceId(appContext)
                val profileId = root.optJSONObject("tablet")?.optString(deviceId)?.takeIf { it.isNotBlank() }
                    ?: root.optString("predefinito")
                val profile = root.optJSONObject("profili")?.optJSONObject(profileId)
                if (profile == null) {
                    Log.w(TAG, "Profilo '$profileId' non definito: resto sul profilo attuale")
                    return@thread
                }
                val apps = parseApps(profile.optJSONArray("apps") ?: JSONArray())
                if (apps.isEmpty()) {
                    Log.w(TAG, "Profilo '$profileId' senza app valide: ignorato")
                    return@thread
                }
                val appsJson = JSONArray().apply {
                    apps.forEach {
                        put(JSONObject().put("packageName", it.packageName).put("displayName", it.displayName)
                            .put("permessi", JSONArray(it.permissions)))
                    }
                }.toString()
                val settingsJson = (profile.optJSONObject("impostazioni") ?: JSONObject()).toString()
                val name = profile.optString("nome", profileId)
                val p = prefs(appContext)
                if (p.getString(KEY_ID, null) == profileId && p.getString(KEY_NAME, null) == name &&
                    p.getString(KEY_APPS, null) == appsJson && p.getString(KEY_SETTINGS, null) == settingsJson) {
                    Log.i(TAG, "Profilo '$profileId' invariato")
                    return@thread
                }
                p.edit().putString(KEY_ID, profileId).putString(KEY_NAME, name).putString(KEY_APPS, appsJson)
                    .putString(KEY_SETTINGS, settingsJson).apply()
                Log.i(TAG, "Profilo aggiornato: '$profileId' (${apps.size} app) per $deviceId")
                Handler(Looper.getMainLooper()).post(onChanged)
            } catch (e: Exception) {
                Log.w(TAG, "Aggiornamento profili fallito (tentativo $attempt, non bloccante): ${e.message}")
                // Tipico all'accensione: la rete non è ancora pronta. Si riprova
                // dopo un minuto (fino a 5 volte) invece di aspettare 30 minuti.
                if (attempt < MAX_ATTEMPTS) {
                    Handler(Looper.getMainLooper()).postDelayed(
                        { refresh(appContext, attempt + 1, onChanged) }, RETRY_DELAY_MS)
                }
            }
        }
    }

    /** Legge il nome del tablet dal catalogo; true se è cambiato. Errori: ignorati. */
    private fun refreshTabletName(context: Context): Boolean = try {
        val cat = JSONObject(download("$CATALOG_URL?t=${System.currentTimeMillis() / 60000}"))
        val name = cat.optJSONObject(StatusReporter.deviceId(context))?.optString("nome")?.trim().orEmpty()
        val p = prefs(context)
        if (p.getString(KEY_TABLET_NAME, "") != name) {
            p.edit().putString(KEY_TABLET_NAME, name).apply()
            Log.i(TAG, "Nome del tablet: '$name'")
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "Nome del tablet non letto (non bloccante): ${e.message}")
        false
    }

    private fun parseApps(array: JSONArray): List<LauncherApp> =
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val pkg = obj.optString("packageName")
            when {
                pkg.isBlank() || pkg == "com.esempio.app" -> null
                pkg in FORBIDDEN -> { Log.w(TAG, "Pacchetto vietato nel profilo, scartato: $pkg"); null }
                else -> LauncherApp(pkg, obj.optString("displayName", pkg),
                    obj.optJSONArray("permessi")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
                        ?: emptyList())
            }
        }

    private fun verify(data: String, sigB64: String): Boolean = try {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(sigB64, Base64.DEFAULT))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Verifica firma fallita: ${e.message}")
        false
    }

    // Dalla 1.5.0 via API GitHub con token (repo privato), vedi DownloadUtils.
    private fun download(url: String): String = DownloadUtils.downloadText(url)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
