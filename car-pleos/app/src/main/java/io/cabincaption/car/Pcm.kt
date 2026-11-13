package io.cabincaption.car

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** PCM16 mono little-endian 바이트 배열 도구. */
object Pcm {
    /** 앱 게인 상한. 기본 출력을 낮게 둔다(ADR-0002 "차 재생 공통"). */
    const val GAIN_CAP = 0.7f

    /** 리미터 천장(-6dBFS). 게인을 곱한 뒤 최고점이 이 값을 넘으면 전체를 더 줄인다. */
    const val CEILING = 0.5f

    const val CHIME_HZ = 880.0
    const val CHIME_MS = 120
    const val CHIME_AMPLITUDE = 0.2
    const val GAP_MS = 180

    /** 알림음 앞 무음. 출력이 깨어나는 동안 첫소리가 잘려 중간부터 튀어나오지 않게 한다. */
    const val LEAD_MS = 40
    const val CHIME_FADE_MS = 10

    /** 음성 앞뒤 페이드. 첫·끝 샘플이 0이 아니어도 무음 → 음성, 음성 → 정지 경계가 튀지 않는다. */
    const val SPEECH_FADE_MS = 5

    fun peak(pcm: ByteArray): Int {
        var p = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = abs(sample(pcm, i))
            if (v > p) p = v
            i += 2
        }
        return p
    }

    /** 배율 = min(게인 상한, 천장 ÷ 최고점). 자르지 않고 전체를 같은 배율로 줄인다(키우지 않음). */
    fun scaleFor(peak: Int, gain: Float = GAIN_CAP, ceiling: Float = CEILING): Float {
        if (peak <= 0) return min(gain, 1f)
        return minOf(gain, ceiling * 32767f / peak, 1f)
    }

    fun limit(pcm: ByteArray, gain: Float = GAIN_CAP, ceiling: Float = CEILING): ByteArray {
        val scale = scaleFor(peak(pcm), gain, ceiling)
        val out = ByteArray(pcm.size and 1.inv())
        var i = 0
        while (i + 1 < pcm.size) {
            put(out, i, (sample(pcm, i) * scale).roundToInt())
            i += 2
        }
        return out
    }

    /** 재생 앞 알림음: 무음 [LEAD_MS] + 사인파(앞뒤 raised-cosine 페이드) + 무음 [GAP_MS]. 앱이 만들어 음성 라이선스가 없다. */
    fun chime(rate: Int): ByteArray {
        val lead = rate * LEAD_MS / 1000
        val tone = rate * CHIME_MS / 1000
        val gap = rate * GAP_MS / 1000
        val fade = rate * CHIME_FADE_MS / 1000
        val out = ByteArray((lead + tone + gap) * 2)
        for (n in 0 until tone) {
            val v = sin(2 * PI * CHIME_HZ * n / rate) * CHIME_AMPLITUDE * ramp(min(n, tone - 1 - n), fade)
            put(out, (lead + n) * 2, (v * 32767).roundToInt())
        }
        return out
    }

    fun withChime(speech: ByteArray, rate: Int): ByteArray = chime(rate) + fadeEdges(limit(speech), rate * SPEECH_FADE_MS / 1000)

    /** 앞뒤 [frames]개 샘플에 raised-cosine 페이드를 건다(제자리). */
    fun fadeEdges(pcm: ByteArray, frames: Int): ByteArray {
        val n = pcm.size / 2
        val f = min(frames, n / 2)
        for (k in 0 until f) {
            val g = ramp(k, f)
            put(pcm, k * 2, (sample(pcm, k * 2) * g).roundToInt())
            put(pcm, (n - 1 - k) * 2, (sample(pcm, (n - 1 - k) * 2) * g).roundToInt())
        }
        return pcm
    }

    /** 0(k=0) → 1(k≥len) raised-cosine. */
    private fun ramp(k: Int, len: Int): Double =
        if (k >= len) 1.0 else 0.5 * (1 - cos(PI * k / len))

    private fun sample(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)

    private fun put(b: ByteArray, i: Int, v: Int) {
        val c = v.coerceIn(-32768, 32767)
        b[i] = c.toByte()
        b[i + 1] = (c shr 8).toByte()
    }
}
