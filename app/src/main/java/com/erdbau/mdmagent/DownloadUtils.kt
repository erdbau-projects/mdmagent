package com.erdbau.mdmagent

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Download condivisi (SelfUpdater, SilentAppInstaller, ProfileManager).
 *
 * Dalla 1.5.0 i file del NOSTRO repo passano dall'API di GitHub con il token
 * dell'app, così tutto continua a funzionare anche con il repo privato:
 *  - raw.githubusercontent.com/<repo>/main/<file>  -> API contents (raw)
 *  - github.com/<repo>/releases/download/<tag>/<f> -> API asset della release
 * Se l'API fallisce si riprova l'indirizzo pubblico originale (utile finché il
 * repo è pubblico, o se il token fosse scaduto). Gli altri indirizzi restano
 * invariati e senza token.
 */
object DownloadUtils {

    private const val TAG = "DownloadUtils"
    private const val REPO = "erdbau-projects/mdmagent"
    private const val API = "https://api.github.com/repos/$REPO"
    private const val RAW_PREFIX = "https://raw.githubusercontent.com/$REPO/main/"
    private const val RELEASE_PREFIX = "https://github.com/$REPO/releases/download/"

    fun downloadToFile(url: String, outFile: File) {
        val connection = open(url, 30_000)
        try {
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun downloadText(url: String, timeoutMs: Int = 15_000): String {
        val connection = open(url, timeoutMs)
        try {
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Connessione già aperta con risposta 2xx, oppure eccezione. */
    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val token = BuildConfig.GITHUB_STATUS_TOKEN
        if (token.isNotBlank() && (url.startsWith(RAW_PREFIX) || url.startsWith(RELEASE_PREFIX))) {
            try {
                return if (url.startsWith(RAW_PREFIX)) {
                    val path = url.removePrefix(RAW_PREFIX).substringBefore('?')
                    get("$API/contents/$path?ref=main", timeoutMs, token, "application/vnd.github.raw")
                } else {
                    openReleaseAsset(url.removePrefix(RELEASE_PREFIX), timeoutMs, token)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download via API fallito ($url): ${e.message} — provo l'indirizzo pubblico")
            }
        }
        return get(url, timeoutMs, null, null)
    }

    /** "<tag>/<nome file>" -> id dell'asset dalla release -> download (redirect senza token). */
    private fun openReleaseAsset(tagAndName: String, timeoutMs: Int, token: String): HttpURLConnection {
        val tag = tagAndName.substringBefore('/')
        val name = tagAndName.substringAfter('/').substringBefore('?')
        val rel = get("$API/releases/tags/$tag", timeoutMs, token, "application/vnd.github+json")
        val json = try {
            JSONObject(rel.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            rel.disconnect()
        }
        val assets = json.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name }
            ?: throw IOException("asset $name non trovato nella release $tag")
        // L'API risponde con un redirect verso lo storage di GitHub: lo si segue
        // a mano, SENZA token (lo storage rifiuta richieste con due autenticazioni).
        val c = connection(asset.getString("url"), timeoutMs, token, "application/octet-stream")
        c.instanceFollowRedirects = false
        val code = c.responseCode
        if (code in 200..299) return c
        val location = c.getHeaderField("Location")
        c.disconnect()
        if (code !in 300..399 || location == null) throw IOException("HTTP $code scaricando l'asset $name")
        return get(location, timeoutMs, null, null)
    }

    private fun connection(url: String, timeoutMs: Int, token: String?, accept: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (accept != null) c.setRequestProperty("Accept", accept)
        return c
    }

    private fun get(url: String, timeoutMs: Int, token: String?, accept: String?): HttpURLConnection {
        val c = connection(url, timeoutMs, token, accept)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code scaricando ${url.substringBefore('?')}")
        }
        return c
    }

    fun computeSha256Base64Url(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return Base64.encodeToString(digest.digest(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
