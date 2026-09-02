package com.erdbau.mdmagent

import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Helper di download/checksum condivisi tra SilentAppInstaller e SelfUpdater. */
object DownloadUtils {

    fun downloadToFile(url: String, outFile: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.connect()

        if (connection.responseCode !in 200..299) {
            throw IOException("HTTP ${connection.responseCode} scaricando $url")
        }

        connection.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
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
