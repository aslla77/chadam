package io.cabincaption.phone

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    /** QR을 카메라 Y 평면처럼 만든다: 회색 배경, 한 줄 끝에 rowStride 여분, 마지막 줄은 짧게. */
    private fun frame(text: String, width: Int, height: Int, stride: Int, size: Int): ByteArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4))
        val out = ByteArray(stride * (height - 1) + width) { 0x80.toByte() }
        val x0 = (width - m.width) / 2
        val y0 = (height - m.height) / 2
        for (y in 0 until m.height) for (x in 0 until m.width) {
            out[(y0 + y) * stride + x0 + x] = if (m.get(x, y)) 20.toByte() else 235.toByte()
        }
        return out.copyOf(stride * height)
    }

    @Test
    fun decodesCarJoinQrFromPaddedLumaPlane() {
        val uri = "cabincaption://join?h=10.2.52.88&p=47100&t=AbCdEfGhIjKlMnOpQrSt_-"
        val d = QrDecoder()
        assertEquals(uri, d.decode(frame(uri, 640, 360, 704, 300), 704, 640, 360))
        assertEquals(uri, d.decode(frame(uri, 640, 360, 640, 200), 640, 640, 360))
    }

    @Test
    fun returnsNullWhenNoQr() {
        assertNull(QrDecoder().decode(ByteArray(640 * 360) { 0x80.toByte() }, 640, 640, 360))
    }
}
