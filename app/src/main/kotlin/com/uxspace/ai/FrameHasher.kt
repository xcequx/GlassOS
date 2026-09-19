package com.uxspace.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

/** Cheap perceptual hash so we don't upload 30 identical frames per second. */
object FrameHasher {

    fun dHash(jpeg: ByteArray): Long {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return 0L
        val scaled = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        if (scaled !== bmp) bmp.recycle()
        var hash = 0L
        for (y in 0 until 8) {
            var prev = luminance(scaled, 0, y)
            for (x in 1 until 9) {
                val cur = luminance(scaled, x, y)
                hash = hash shl 1
                if (cur > prev) hash = hash or 1L
                prev = cur
            }
        }
        scaled.recycle()
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun changed(previous: Long, next: Long, threshold: Int = 8): Boolean =
        previous == 0L || abs(hamming(previous, next)) >= threshold

    private fun luminance(bmp: Bitmap, x: Int, y: Int): Int {
        val c = bmp.getPixel(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }
}
