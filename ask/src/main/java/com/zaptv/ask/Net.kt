package com.zaptv.ask

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android TV) ZapAsk/1.0"

    fun open(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: ByteArray? = null, timeout: Int = 30000): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.requestMethod = method
            c.setRequestProperty("User-Agent", headers["User-Agent"] ?: UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            for ((k, v) in headers) if (k != "User-Agent") c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location") ?: return c).toString()
                c.disconnect()
            } else return c
        }
        throw java.io.IOException("Too many redirects")
    }

    private fun bodyStream(c: HttpURLConnection): InputStream {
        val raw = BufferedInputStream(if (c.responseCode >= 400) (c.errorStream ?: c.inputStream) else c.inputStream)
        val enc = c.contentEncoding
        return if (enc != null && enc.contains("gzip", true)) GZIPInputStream(raw) else raw
    }

    fun text(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: ByteArray? = null): String {
        val c = open(url, headers, method, body)
        val code = c.responseCode
        val s = bodyStream(c).use { it.readBytes().toString(Charsets.UTF_8) }
        if (code >= 400) throw java.io.IOException("HTTP $code: ${s.take(200)}")
        return s
    }

    fun bytes(url: String): ByteArray? = runCatching {
        val c = open(url)
        if (c.responseCode >= 400) return null
        bodyStream(c).use { it.readBytes() }
    }.getOrNull()
}
