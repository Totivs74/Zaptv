package com.zaptv.app

data class Channel(
    val name: String,
    val group: String,
    val logo: String,
    val url: String,
    val tvgId: String,
    /** MAC portal command; when set, the real URL is fetched just before playback. */
    val cmd: String = "",
    var number: Int = 0,
    /** Playlist this channel came from. */
    val pl: String = "",
    /** Small text shown in the tile corner instead of the number (e.g. a temperature). */
    var badge: String = ""
) {
    val isWeather: Boolean get() = url.startsWith("weather:")
    val key: String get() = "$name|$group"
    /** Clean label used on tiles when there is no logo: "TR: Kanal D, HD" -> "Kanal D". */
    val initials: String
        get() = name
            .replace(Regex("(?i)^(tr|tur|turk|turkey|türkiye)\\s*[:|\\-]+\\s*"), "")
            .replace(Regex("(?i)\\b(hd|fhd|uhd|sd|4k|hevc)\\b"), "")
            .trim().trim(',', '|', '-', ' ')
            .ifEmpty { name }
}

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String) {
    fun progress(now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
