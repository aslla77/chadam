package io.cabincaption.protocol

import java.io.InputStream
import java.io.OutputStream

/** 프레임 = 종류(1B) + 본문 길이(4B, big-endian) + 본문. ADR-0004. */
object FrameCodec {
    const val HEADER_SIZE = 5
    const val MAX_BODY = 64 * 1024

    fun encode(frame: Frame): ByteArray {
        val n = frame.body.size
        require(n <= MAX_BODY) { "body $n > $MAX_BODY" }
        val out = ByteArray(HEADER_SIZE + n)
        out[0] = frame.type.code.toByte()
        out[1] = (n ushr 24).toByte()
        out[2] = (n ushr 16).toByte()
        out[3] = (n ushr 8).toByte()
        out[4] = n.toByte()
        frame.body.copyInto(out, HEADER_SIZE)
        return out
    }

    fun write(out: OutputStream, frame: Frame) {
        out.write(encode(frame))
        out.flush()
    }

    /** 프레임 경계에서 스트림이 끝나면 null. 프레임 중간에서 끝나거나 형식이 틀리면 [ProtocolException]. */
    fun read(input: InputStream, maxBody: Int = MAX_BODY): Frame? {
        val first = input.read()
        if (first < 0) return null
        val type = FrameType.of(first) ?: throw ProtocolException("unknown frame type $first")
        val len = readExactly(input, 4).let {
            ((it[0].toInt() and 0xFF) shl 24) or ((it[1].toInt() and 0xFF) shl 16) or
                ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF)
        }
        if (len < 0 || len > maxBody) throw ProtocolException("body length $len out of 0..$maxBody")
        return Frame(type, readExactly(input, len))
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw ProtocolException("truncated frame: $off of $n bytes")
            off += r
        }
        return buf
    }
}
