package io.cabincaption.protocol

/**
 * 차 마이크 청크(CAR_PCM 본문) = 순번(4B, 부호 없는 big-endian) + 캡처 시각(8B, 차 시계 ms, big-endian) + PCM16 16kHz mono LE.
 * 순번은 업로드하지 않은 청크에도 매긴다. 그래서 폰이 받은 순번의 빈 곳 = 차가 버린 구간(멈춤·재생 중·폰 0대).
 */
class CarChunk(val seq: Long, val captureMs: Long, val pcm: ByteArray) {
    fun frame(): Frame {
        val out = ByteArray(HEADER + pcm.size)
        val s = seq and 0xFFFF_FFFFL
        for (i in 0 until 4) out[i] = (s ushr (24 - 8 * i)).toByte()
        for (i in 0 until 8) out[4 + i] = (captureMs ushr (56 - 8 * i)).toByte()
        pcm.copyInto(out, HEADER)
        return Frame(FrameType.CAR_PCM, out)
    }

    companion object {
        const val HEADER = 12
        const val RATE = 16_000

        /** 100ms = 16,000Hz × 2바이트 × 0.1초. */
        const val CHUNK_BYTES = 3_200

        /** 본문이 짧거나 PCM이 비었거나 홀수 바이트면 null. */
        fun parse(body: ByteArray): CarChunk? {
            val n = body.size - HEADER
            if (n <= 0 || n % 2 != 0) return null
            var seq = 0L
            for (i in 0 until 4) seq = (seq shl 8) or (body[i].toLong() and 0xFF)
            var t = 0L
            for (i in 0 until 8) t = (t shl 8) or (body[4 + i].toLong() and 0xFF)
            return CarChunk(seq, t, body.copyOfRange(HEADER, body.size))
        }
    }
}
