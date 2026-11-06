package com.zaptv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Small logo loader: memory + disk cache, no extra libraries. */
object Logos {
    private val mem = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dir: File

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "logos").apply { mkdirs() }
    }

    fun clearDisk() {
        dir.listFiles()?.forEach { it.delete() }
        mem.evictAll()
        failed.clear()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Shows the logo in [iv]; shows [fallback] (e.g. channel initials) when there is none. */
    fun load(url: String, iv: ImageView, fallback: View?) {
        iv.tag = url
        if (url.isEmpty() || url in failed) {
            iv.setImageDrawable(null); fallback?.visibility = View.VISIBLE; return
        }
        val hit = mem.get(url)
        if (hit != null) {
            iv.setImageBitmap(hit); fallback?.visibility = View.GONE; return
        }
        iv.setImageDrawable(null)
        fallback?.visibility = View.VISIBLE
        pool.execute {
            val bmp = runCatching { fetch(url) }.getOrNull()
            if (bmp == null) failed.add(url) else mem.put(url, bmp)
            main.post {
                if (iv.tag == url) {
                    if (bmp != null) { iv.setImageBitmap(bmp); fallback?.visibility = View.GONE }
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val f = File(dir, hash(url))
        if (!f.exists() || f.length() == 0L) Http.download(url, f)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 220 && o.outHeight / (sample * 2) >= 120) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
