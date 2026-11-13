package io.cabincaption.car

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrMatrixTest {

    @Test
    fun encodedQrDecodesBackToTheSameUri() {
        val uri = JoinTarget("10.2.52.88", 47100).uri("Zm9vYmFyYmF6cXV4MTIzNA")
        val m = QrMatrix.encode(uri)
        val scale = 4
        val size = m.width * scale
        val pixels = IntArray(size * size) { i ->
            if (m.get((i % size) / scale, (i / size) / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
        assertEquals(uri, decoded.text)
    }
}
