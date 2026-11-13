package io.cabincaption.phone

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TTS synthesizeToFile 출력에서 PCM16 mono를 꺼낸다.
 * 파일이면 WAV 헤더가 있다. 파이프면 플랫폼이 헤더 자리(44바이트)를 0으로 비워 두고, 끝에서 헤더를 쓰려고 되감다가
 * "Illegal seek"로 실패한다(Note20 실측 2026-10-29, AOSP FileSynthesisCallback.done). 그래서 형식은 onBeginSynthesis 값을 쓴다.
 */
object WavPcm {
    const val HEADER_BYTES = 44

    /** android.media.AudioFormat.ENCODING_PCM_16BIT 값. JVM 테스트를 위해 상수로 둔다. */
    const val ENCODING_PCM_16BIT = 2

    class Pcm(val rate: Int, val data: ByteArray)

    /** onBeginSynthesis 형식([rate]·[encoding]·[channels])과 파이프 바이트로. PCM16 mono가 아니거나 앞 44바이트가 비어 있지 않으면 null. */
    fun fromTtsPipe(bytes: ByteArray, rate: Int, encoding: Int, channels: Int): Pcm? {
        if (encoding != ENCODING_PCM_16BIT || channels != 1 || rate <= 0) return null
        if (isRiff(bytes)) return parse(bytes)?.takeIf { it.rate == rate }
        if (bytes.size < HEADER_BYTES || (0 until HEADER_BYTES).any { bytes[it] != 0.toByte() }) return null
        val n = (bytes.size - HEADER_BYTES) and 1.inv()
        return Pcm(rate, bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + n))
    }

    private fun isRiff(b: ByteArray) =
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE"

    /** 헤더가 제대로 쓰인 WAV(파일 방식). data 길이 칸은 믿지 않고 끝까지를 PCM으로 본다. PCM16 mono가 아니면 null. */
    fun parse(wav: ByteArray): Pcm? {
        if (!isRiff(wav)) return null
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = -1
        while (pos + 8 <= wav.size) {
            val tag = String(wav, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            if (tag == "fmt ") {
                if (len < 16 || body + 16 > wav.size) return null
                val format = bb.getShort(body).toInt()
                val channels = bb.getShort(body + 2).toInt()
                val bits = bb.getShort(body + 14).toInt()
                if (format != 1 || channels != 1 || bits != 16) return null
                rate = bb.getInt(body + 4)
            }
            if (tag == "data") {
                if (rate <= 0) return null
                val n = (wav.size - body) and 1.inv()
                return Pcm(rate, wav.copyOfRange(body, body + n))
            }
            if (len < 0 || len > wav.size) return null
            pos = body + len + (len and 1)
        }
        return null
    }
}
