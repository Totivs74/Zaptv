package com.zaptv.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object Qr {
    fun bitmap(text: String, size: Int): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
