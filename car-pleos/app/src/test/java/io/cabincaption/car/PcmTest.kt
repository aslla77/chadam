package io.cabincaption.car

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            b[i * 2] = v.toByte()
            b[i * 2 + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun samples(b: ByteArray) = (0 until b.size / 2).map { (b[it * 2].toInt() and 0xFF) or (b[it * 2 + 1].toInt() shl 8) }

    @Test
    fun peakReadsSignedLittleEndian() {
        assertEquals(32768, Pcm.peak(pcm(100, -32768, 5)))
        assertEquals(0, Pcm.peak(ByteArray(0)))
    }

    @Test
    fun quietSpeechGetsGainCapNotBoost() {
        val out = samples(Pcm.limit(pcm(1000, -1000)))
        assertEquals(listOf(700, -700), out)
    }

    @Test
    fun loudSpeechIsLimitedToCeiling() {
        val out = samples(Pcm.limit(pcm(32767, -32767, 16000)))
        val ceiling = (Pcm.CEILING * 32767).toInt()
        assertTrue(out.all { kotlin.math.abs(it) <= ceiling + 1 })
        assertTrue(kotlin.math.abs(out[0]) >= ceiling - 1)
    }

    @Test
    fun ttsLikePeakUsesTheSmallerFactor() {
        val f = Pcm.scaleFor(25358)
        assertEquals(Pcm.CEILING * 32767f / 25358, f, 1e-4f)
        assertTrue(f < Pcm.GAIN_CAP)
    }

    @Test
    fun limitDropsTrailingOddByte() {
        assertEquals(2, Pcm.limit(byteArrayOf(1, 0, 7)).size)
    }

    @Test
    fun chimeIsLeadSilenceQuietToneThenSilence() {
        val rate = 24_000
        val c = Pcm.chime(rate)
        val lead = rate * Pcm.LEAD_MS / 1000
        val tone = rate * Pcm.CHIME_MS / 1000
        val gap = rate * Pcm.GAP_MS / 1000
        assertEquals((lead + tone + gap) * 2, c.size)
        val s = samples(c)
        assertTrue(s.take(lead).all { it == 0 })
        assertTrue(s.subList(lead, lead + tone).maxOf { abs(it) } in 6000..6600)
        assertTrue(s.drop(lead + tone).all { it == 0 })
    }

    // 오너가 들은 알림음 잡음(2026-11-03): 톤 앞뒤가 0에서 부드럽게 오르내리고, 어디서도 사인파 기울기보다 크게 튀지 않는다
    @Test
    fun chimeHasNoClickAtAnyRate() {
        for (rate in listOf(8_000, 16_000, 22_050, 24_000, 44_100, 48_000)) {
            val s = samples(Pcm.chime(rate))
            val lead = rate * Pcm.LEAD_MS / 1000
            val tone = rate * Pcm.CHIME_MS / 1000
            assertTrue("rate=$rate start", abs(s[lead]) <= 1 && abs(s[lead + 1]) <= 30)
            assertTrue("rate=$rate end", abs(s[lead + tone - 1]) <= 1 && abs(s[lead + tone - 2]) <= 30)
            val peak = s.maxOf { abs(it) }
            assertTrue("rate=$rate peak=$peak", peak <= Pcm.CHIME_AMPLITUDE * 32767 + 1)
            val sineStep = Pcm.CHIME_AMPLITUDE * 32767 * 2 * PI * Pcm.CHIME_HZ / rate
            val maxStep = s.zipWithNext { a, b -> abs(b - a) }.max()
            assertTrue("rate=$rate step=$maxStep limit=$sineStep", maxStep <= sineStep * 1.05 + 1)
        }
    }

    @Test
    fun withChimePrependsChimeToLimitedSpeech() {
        val speech = pcm(*IntArray(400) { 1000 })
        val out = samples(Pcm.withChime(speech, 16_000))
        assertEquals(Pcm.chime(16_000).size / 2 + 400, out.size)
        assertEquals(700, out[out.size - 200])
    }

    // 음성이 0이 아닌 값으로 시작·끝나도(직류 16000) 알림음 무음 → 음성, 음성 → 정지 경계에서 튀지 않는다
    @Test
    fun speechEdgesAreFadedSoBoundariesDoNotJump() {
        val rate = 16_000
        val out = samples(Pcm.withChime(pcm(*IntArray(rate / 2) { 16_000 }), rate))
        val start = Pcm.chime(rate).size / 2
        assertEquals(0, out[start - 1])
        assertEquals(0, out[start])
        assertEquals(0, out.last())
        val level = (16_000 * Pcm.GAIN_CAP).toInt()
        val fade = rate * Pcm.SPEECH_FADE_MS / 1000
        val maxStep = out.drop(start - 1).zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("step=$maxStep", maxStep <= level * PI / (2 * fade) + 2)
        assertEquals(level, out[start + fade])
    }

    @Test
    fun fadeEdgesLeavesTinyBuffersSafe() {
        assertEquals(0, Pcm.fadeEdges(ByteArray(0), 80).size)
        assertEquals(listOf(5000), samples(Pcm.fadeEdges(pcm(5000), 80)))
    }
}
