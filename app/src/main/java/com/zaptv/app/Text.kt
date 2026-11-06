package com.zaptv.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Norm {
    private val TR = Locale("tr")
    private val fold = mapOf(
        'ı' to 'i', 'ş' to 's', 'ğ' to 'g', 'ü' to 'u', 'ö' to 'o', 'ç' to 'c',
        'â' to 'a', 'î' to 'i', 'û' to 'u', 'é' to 'e', 'è' to 'e', 'ä' to 'a', 'ë' to 'e'
    )
    private val prefix = Regex("^(tr|tur|turk|turkey|turkiye)\\s*[:|\\-]+\\s*")
    private val noise = Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|h264|backup|yedek|raw)\\b")

    /** Lowercase + Turkish letters folded to ASCII, spaces kept. */
    fun words(s: String): String {
        val lower = s.lowercase(TR)
        val sb = StringBuilder(lower.length)
        for (c in lower) sb.append(fold[c] ?: c)
        return sb.toString()
    }

    /** Compact key used to match channel names: "TR: Kanal D HD" -> "kanald". */
    fun key(s: String): String {
        var w = words(s).trim()
        w = prefix.replace(w, "")
        w = noise.replace(w, " ")
        return w.filter { it.isLetterOrDigit() }
    }
}

object Fmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.UK)
    private val day = SimpleDateFormat("EEEE, d MMMM", Locale.UK)
    fun time(ms: Long): String = synchronized(hm) { hm.format(Date(ms)) }
    fun range(p: Programme): String = time(p.start) + " - " + time(p.stop)
    fun today(): String = synchronized(day) { day.format(Date()) }
}
