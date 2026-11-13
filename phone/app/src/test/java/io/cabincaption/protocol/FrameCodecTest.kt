package io.cabincaption.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun read(raw: ByteArray, max: Int = FrameCodec.MAX_BODY) = FrameCodec.read(ByteArrayInputStream(raw), max)

    @Test
    fun headerIsTypeThenBigEndianLength() {
        val raw = FrameCodec.encode(Frame(FrameType.CTRL, bytes(0xAA, 0xBB, 0xCC)))
        assertArrayEquals(bytes(2, 0, 0, 0, 3, 0xAA, 0xBB, 0xCC), raw)
    }

    @Test
    fun roundTripsEveryType() {
        val out = ByteArrayOutputStream()
        val frames = FrameType.entries.map { Frame(it, ByteArray(it.code * 10) { i -> i.toByte() }) }
        frames.forEach { FrameCodec.write(out, it) }
        val input = ByteArrayInputStream(out.toByteArray())
        frames.forEach { assertEquals(it, FrameCodec.read(input)) }
        assertNull(FrameCodec.read(input))
    }

    @Test
    fun emptyBodyAndMaxBodyAreAllowed() {
        assertEquals(Frame(FrameType.HELLO, ByteArray(0)), read(FrameCodec.encode(Frame(FrameType.HELLO, ByteArray(0)))))
        val max = Frame(FrameType.CAR_PCM, ByteArray(FrameCodec.MAX_BODY))
        assertEquals(max, read(FrameCodec.encode(max)))
    }

    @Test
    fun emptyStreamIsCleanEnd() {
        assertNull(read(ByteArray(0)))
    }

    @Test
    fun truncatedHeaderIsRejected() {
        assertThrows(ProtocolException::class.java) { read(bytes(1)) }
        assertThrows(ProtocolException::class.java) { read(bytes(1, 0, 0)) }
    }

    @Test
    fun truncatedBodyIsRejected() {
        val e = assertThrows(ProtocolException::class.java) { read(bytes(3, 0, 0, 0, 4, 9, 9)) }
        assertTrue(e.message!!.contains("truncated"))
    }

    @Test
    fun lengthOverLimitIsRejectedBeforeReadingBody() {
        val over = FrameCodec.MAX_BODY + 1
        val raw = bytes(4, over ushr 24, (over ushr 16) and 0xFF, (over ushr 8) and 0xFF, over and 0xFF)
        assertThrows(ProtocolException::class.java) { read(raw) }
    }

    @Test
    fun negativeLengthIsRejected() {
        assertThrows(ProtocolException::class.java) { read(bytes(4, 0xFF, 0xFF, 0xFF, 0xFF)) }
    }

    @Test
    fun callerLimitIsEnforced() {
        val raw = FrameCodec.encode(Frame(FrameType.HELLO, ByteArray(Wire.HELLO_MAX_BODY + 1)))
        assertThrows(ProtocolException::class.java) { read(raw, Wire.HELLO_MAX_BODY) }
    }

    @Test
    fun unknownTypeIsRejected() {
        assertThrows(ProtocolException::class.java) { read(bytes(0, 0, 0, 0, 0)) }
        assertThrows(ProtocolException::class.java) { read(bytes(5, 0, 0, 0, 0)) }
    }

    @Test
    fun encodingOversizedBodyFails() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.encode(Frame(FrameType.CAR_PCM, ByteArray(FrameCodec.MAX_BODY + 1)))
        }
    }

    @Test
    fun readsAcrossSplitInput() {
        val raw = FrameCodec.encode(Frame(FrameType.CAR_PCM, ByteArray(3200) { 7 }))
        val trickle = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < raw.size) raw[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= raw.size) return -1
                b[off] = raw[i++]
                return 1
            }
        }
        assertEquals(3200, FrameCodec.read(trickle)!!.body.size)
    }
}
