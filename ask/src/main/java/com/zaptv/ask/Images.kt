package com.zaptv.ask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Finds a real web photo for a card.
 * Preferred: Google Programmable Search (image mode) when the user added that key.
 * Fallback: the "preview image" (og:image) of the card's own source link.
 */
class Images(ctx: Context, private val prefs: Prefs) {
    private val dir = File(ctx.cacheDir, "img").apply { mkdirs() }

    private fun hash(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun forCard(card: Card): ByteArray? {
        val cached = File(dir, hash(card.imageQuery + "|" + card.link))
        if (cached.exists() && cached.length() > 0) return cached.readBytes()
        var bytes = searchImage(card.imageQuery)
        if (bytes == null && card.link.isNotEmpty()) bytes = previewImage(card.link)
        if (bytes != null) {
            val small = shrink(bytes)
            runCatching { cached.writeBytes(small) }
            return small
        }
        return null
    }

    private fun searchImage(query: String): ByteArray? {
        val cx = prefs.imageCx
        val k = prefs.imageKey
        if (query.isBlank() || cx.isBlank() || k.isBlank()) return null
        return runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/customsearch/v1?q=$q&searchType=image&num=3&safe=active&imgSize=large&key=$k&cx=$cx"
            val js = JSONObject(Net.text(url))
            val items = js.optJSONArray("items") ?: return null
            for (i in 0 until items.length()) {
                val link = items.getJSONObject(i).optString("link")
                if (link.startsWith("http")) Net.bytes(link)?.let { if (looksLikeImage(it)) return it }
            }
            null
        }.getOrNull()
    }

    private val ogRegex = Regex("<meta[^>]+(?:property|name)=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val ogRegex2 = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:image[\"']", RegexOption.IGNORE_CASE)

    private fun previewImage(pageUrl: String): ByteArray? = runCatching {
        val html = Net.text(pageUrl).take(200_000)
        val m = ogRegex.find(html) ?: ogRegex2.find(html) ?: return null
        var img = m.groupValues[1]
        if (img.startsWith("//")) img = "https:$img"
        if (!img.startsWith("http")) return null
        Net.bytes(img)?.takeIf { looksLikeImage(it) }
    }.getOrNull()

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 100) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte()
        val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()
        val webp = b.size > 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()
        return jpg || png || gif || webp
    }

    /** Scale down to about 640px wide to save memory on the TV. */
    private fun shrink(bytes: ByteArray): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 640) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }
}
