package com.zaptv.app

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Http {
    const val UA = "ZapTV/1.0 (Linux; Android TV)"

    /** Opens a GET connection and follows redirects, including http <-> https hops. */
    fun open(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20000): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", headers["User-Agent"] ?: UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            for ((k, v) in headers) if (k != "User-Agent") c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
            } else if (code >= 400) {
                c.disconnect()
                throw IOException("Server answered $code")
            } else {
                return c
            }
        }
        throw IOException("Too many redirects")
    }

    /** Body stream; transparently un-gzips both gzip transfer and .gz files. */
    fun stream(url: String, headers: Map<String, String> = emptyMap()): InputStream {
        val c = open(url, headers)
        val raw = BufferedInputStream(c.inputStream, 64 * 1024)
        raw.mark(2)
        val b1 = raw.read()
        val b2 = raw.read()
        raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw
    }

    fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        stream(url, headers).use { it.readBytes().toString(Charsets.UTF_8) }

    fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        stream(url).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
