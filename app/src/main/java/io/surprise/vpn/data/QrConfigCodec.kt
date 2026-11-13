package io.surprise.vpn.data

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
object QrConfigCodec {
    private const val MAX_PAYLOAD_CHARS = 2_900

    fun encode(text: String, size: Int = 900): Bitmap {
        require(text.length <= MAX_PAYLOAD_CHARS) {
            "Configuration is too large for a reliable QR code; use JSON backup instead."
        }
        val matrix: BitMatrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2)
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF10131A.toInt() else 0xFFF6F8FC.toInt())
            }
        }
    }

    fun decode(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
