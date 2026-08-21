package xyz.a202132.windows.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopQrScannerTest {
    @Test
    fun `decodes a QR image payload`() {
        val payload = "vless://id@example.com:443?security=tls#camera-test"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 420, 420)
        val image = MatrixToImageWriter.toBufferedImage(matrix)

        assertEquals(payload, DesktopQrCode.decode(image)?.text)
    }
}
