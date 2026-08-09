package ca.beric.clipsync.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/** Renders [text] as a QR code the phone's camera can scan to pair. */
fun qrImageBitmap(text: String, size: Int = 320): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until size) {
        for (y in 0 until size) {
            image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        }
    }
    return image.toComposeImageBitmap()
}
