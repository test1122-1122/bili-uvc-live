package com.biliuvc.live.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code bitmap helper based on ZXing core. */
object QrCodeUtil {

    fun generate(content: String, sizePx: Int = 640): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(matrix.width * matrix.height)
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
            }
        }.getOrNull()
    }
}
