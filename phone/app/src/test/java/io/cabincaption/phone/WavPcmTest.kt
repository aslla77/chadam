package io.cabincaption.phone

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavPcmTest {

    private fun wav(rate: Int, pcm: ByteArray, format: Int = 1, channels: Int = 1, bits: Int = 16, dataLen: Int = pcm.size, extra: ByteArray? = null): ByteArray {
        val chunks = ByteArray(0).let { if (extra != null) it + chunk("LIST", extra) else it }
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(format.toShort()).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
            .putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).array()
        val dataHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("data".toByteArray()).putInt(dataLen).array()
        val body = "WAVE".toByteArray() + chunk("fmt ", fmt) + chunks + dataHead + pcm
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
    }

    private fun chunk(tag: String, body: ByteArray): ByteArray =
        tag.toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body + (if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    @Test
    fun pipeOutputIsZeroPlaceholderThenPcm() {
        val pcm = ByteArray(1001) { (it % 100 + 1).toByte() }
        val p = WavPcm.fromTtsPipe(ByteArray(44) + pcm, 24_000, WavPcm.ENCODING_PCM_16BIT, 1)!!
        assertEquals(24_000, p.rate)
        assertArrayEquals(pcm.copyOf(1000), p.data)
    }

    @Test
    fun pipeRejectsWrongFormatOrNonEmptyPrefix() {
        val ok = ByteArray(44) + ByteArray(10) { 5 }
        assertNull(WavPcm.fromTtsPipe(ok, 24_000, 3, 1))
        assertNull(WavPcm.fromTtsPipe(ok, 24_000, WavPcm.ENCODING_PCM_16BIT, 2))
        assertNull(WavPcm.fromTtsPipe(ok, 0, WavPcm.ENCODING_PCM_16BIT, 1))
        assertNull(WavPcm.fromTtsPipe(ByteArray(43), 24_000, WavPcm.ENCODING_PCM_16BIT, 1))
        assertNull(WavPcm.fromTtsPipe(byteArrayOf(1) + ByteArray(60), 24_000, WavPcm.ENCODING_PCM_16BIT, 1))
    }

    @Test
    fun pipeAcceptsRealHeaderIfEngineWritesOne() {
        val pcm = ByteArray(8) { 7 }
        assertArrayEquals(pcm, WavPcm.fromTtsPipe(wav(24_000, pcm), 24_000, WavPcm.ENCODING_PCM_16BIT, 1)!!.data)
        assertNull(WavPcm.fromTtsPipe(wav(16_000, pcm), 24_000, WavPcm.ENCODING_PCM_16BIT, 1))
    }

    @Test
    fun fileHeaderIs44BytesAndPcmFollows() {
        val pcm = ByteArray(1000) { it.toByte() }
        val w = wav(24_000, pcm)
        assertEquals(44 + 1000, w.size)
        val p = WavPcm.parse(w)!!
        assertEquals(24_000, p.rate)
        assertArrayEquals(pcm, p.data)
    }

    @Test
    fun ignoresDeclaredDataLengthFromPipe() {
        val pcm = ByteArray(600) { 3 }
        assertEquals(600, WavPcm.parse(wav(24_000, pcm, dataLen = 0))!!.data.size)
        assertEquals(600, WavPcm.parse(wav(24_000, pcm, dataLen = -1))!!.data.size)
    }

    @Test
    fun skipsOtherChunksAndOddTail() {
        val p = WavPcm.parse(wav(16_000, ByteArray(5) { 1 }, extra = byteArrayOf(9, 9, 9)))!!
        assertEquals(16_000, p.rate)
        assertEquals(4, p.data.size)
    }

    @Test
    fun rejectsNonPcm16MonoOrBrokenHeader() {
        assertNull(WavPcm.parse(wav(24_000, ByteArray(4), format = 3)))
        assertNull(WavPcm.parse(wav(24_000, ByteArray(4), channels = 2)))
        assertNull(WavPcm.parse(wav(24_000, ByteArray(4), bits = 8)))
        assertNull(WavPcm.parse(ByteArray(44)))
        assertNull(WavPcm.parse(wav(24_000, ByteArray(4)).copyOf(30)))
    }
}
