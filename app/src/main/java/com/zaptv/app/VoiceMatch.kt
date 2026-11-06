package com.zaptv.app

/** Matches Turkish speech ("Kanal D aç", "TRT bir", "şov tv") to a channel. */
object VoiceMatch {
    private val drop = setOf(
        "ac", "acar", "acarmisin", "misin", "musun", "lutfen", "kanalini", "kanalina", "kanali",
        "izle", "izlemek", "istiyorum", "goster", "gec", "degistir", "hadi", "bana", "simdi", "su", "bir kanal"
    )
    private val numbers = mapOf(
        "sifir" to "0", "bir" to "1", "iki" to "2", "uc" to "3", "dort" to "4", "bes" to "5",
        "alti" to "6", "yedi" to "7", "sekiz" to "8", "dokuz" to "9", "on" to "10"
    )

    fun clean(spoken: String): String {
        val tokens = Norm.words(spoken).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val kept = tokens.filter { it !in drop }.map { numbers[it] ?: it }
        return kept.joinToString("")
    }

    fun best(phrases: List<String>, channels: List<Channel>): Pair<Channel, Double>? {
        var best: Channel? = null
        var bestScore = 0.0
        for (ph in phrases) {
            val q = clean(ph)
            if (q.isEmpty()) continue
            if (q.all { it.isDigit() }) {
                val n = q.toIntOrNull()
                val c = channels.firstOrNull { it.number == n }
                if (c != null) return Pair(c, 1.0)
            }
            for (c in channels) {
                val k = Norm.key(c.name)
                if (k.isEmpty()) continue
                val s = score(q, k)
                if (s > bestScore) { bestScore = s; best = c }
            }
        }
        val b = best ?: return null
        return if (bestScore >= 0.6) Pair(b, bestScore) else null
    }

    private fun score(q: String, k: String): Double = when {
        q == k -> 1.0
        k.startsWith(q) || q.startsWith(k) -> 0.92 - 0.02 * kotlin.math.abs(k.length - q.length)
        k.contains(q) && q.length >= 3 -> 0.8
        else -> 1.0 - lev(q, k).toDouble() / maxOf(q.length, k.length)
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
