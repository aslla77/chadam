package io.cabincaption.phone

import io.cabincaption.phone.CaptionLog.Entry
import io.cabincaption.phone.CaptionLog.NoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLogTest {

    private val log = CaptionLog(maxEntries = 6)
    private val silence = ByteArray(3_200)
    private val speech = ByteArray(3_200).also { b -> for (i in b.indices step 2) { b[i] = 0x10; b[i + 1] = 0x10 } }

    private fun lines() = log.snapshot().filterIsInstance<Entry.Line>()

    @Test
    fun sharedTextIsAddedInOrderOnceAndNumberedApartFromLines() {
        log.partial("앞좌석 말", final = false, nowMs = 0)
        assertTrue(log.shared("1", "뒷좌석 글"))
        assertFalse(log.shared("1", "뒷좌석 글"))
        log.mine("s1")
        assertTrue(log.shared("2", "또 다른 글"))
        assertEquals(
            listOf(Entry.Line(1, "앞좌석 말", false, null), Entry.Shared(1, "1", "뒷좌석 글"), Entry.Mine("s1"), Entry.Shared(2, "2", "또 다른 글")),
            log.snapshot(),
        )
        assertNull(log.partial("앞좌석 말 계속", final = false, nowMs = 0))
        assertEquals("앞좌석 말 계속", lines().single().text)
    }

    @Test
    fun sharedTextIsForgottenWithTheSession() {
        log.shared("1", "지울 글")
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        assertTrue(log.shared("1", "새 세션 글"))
        assertEquals(listOf(Entry.Shared(1, "1", "새 세션 글")), log.snapshot())
    }

    @Test
    fun partialsGrowOneLineThenEndOfSpeechConfirmsIt() {
        assertEquals(1, log.partial("뒤에", final = false, nowMs = 0))
        assertNull(log.partial("뒤에 괜찮아", final = false, nowMs = 0))
        assertEquals(listOf(Entry.Line(1, "뒤에 괜찮아", false, null)), lines())
        log.endOfSpeech()
        assertEquals(true, lines()[0].final)
    }

    @Test
    fun finalPartialAfterEndOfSpeechFixesTheSameLine() {
        log.partial("지금 길이", final = false, nowMs = 0)
        log.endOfSpeech()
        assertNull(log.partial(" 지금 길이 좀 막혀서 ", final = true, nowMs = 0))
        assertEquals(listOf(Entry.Line(1, "지금 길이 좀 막혀서", true, null)), lines())
        assertEquals(2, log.partial("배고프면", final = false, nowMs = 0))
        assertEquals(2, lines().size)
    }

    @Test
    fun repeatedTextAfterEndOfSpeechIsNotANewLine() {
        log.partial("안전벨트 꼭 메고 있어", final = false, nowMs = 0)
        log.endOfSpeech()
        assertNull(log.partial("안전벨트 꼭 메고 있어", final = false, nowMs = 0))
        assertNull(log.partial("안전벨트 꼭 메고 있어", final = true, nowMs = 0))
        assertEquals(listOf(Entry.Line(1, "안전벨트 꼭 메고 있어", true, null)), lines())
    }

    @Test
    fun nonFinalPartialAfterEndOfSpeechStartsNewLine() {
        log.partial("하나", final = false, nowMs = 0)
        log.endOfSpeech()
        assertEquals(2, log.partial("둘", final = false, nowMs = 0))
        assertEquals(listOf("하나", "둘"), lines().map { it.text })
    }

    @Test
    fun blankPartialsDoNotMakeLines() {
        assertNull(log.partial("  ", final = false, nowMs = 0))
        assertNull(log.partial("", final = true, nowMs = 0))
        assertEquals(0, log.size())
    }

    @Test
    fun interruptConfirmsOpenLineAndAddsNotice() {
        log.partial("배고프면 거기서", final = false, nowMs = 0)
        log.interrupt(NoticeKind.PAUSED)
        assertEquals(listOf(Entry.Line(1, "배고프면 거기서", true, null), Entry.Notice(NoticeKind.PAUSED)), log.snapshot())
        assertEquals(2, log.partial("뭐 좀 먹자", final = false, nowMs = 0))
    }

    @Test
    fun mineAndNoticesKeepOrderAndOldestAreTrimmed() {
        log.mine("s1")
        log.partial("a", false, 0)
        log.notice(NoticeKind.MIC_SILENCED)
        log.notice(NoticeKind.MIC_BACK)
        log.mine("s2")
        log.mine("s3")
        log.mine("s4")
        val s = log.snapshot()
        assertEquals(6, s.size)
        assertEquals(Entry.Line(1, "a", false, null), s[0])
        assertEquals(Entry.Mine("s4"), s.last())
    }

    @Test
    fun latencyIsFromSpeechOnsetChunkToFirstPartial() {
        log.clockOffsetMs = 1_000 // 차 시계가 1초 빠름
        log.chunk(0, captureMs = 10_000, rxMs = 9_050, pcm = silence)
        log.chunk(1, captureMs = 10_100, rxMs = 9_150, pcm = speech)
        log.chunk(2, captureMs = 10_200, rxMs = 9_250, pcm = speech)
        log.partial("뒤에", false, nowMs = 9_800)
        assertEquals(700L, lines()[0].latencyMs) // 9,800 + 1,000 − 10,100
        assertEquals(CaptionLog.Stats(1, 700, 700), log.totalStats())
        assertEquals(CaptionLog.Stats(1, 650, 650), log.recognizerStats())
    }

    @Test
    fun onsetNeedsQuietBeforeItAndIsClearedAtLineEnd() {
        log.clockOffsetMs = 0
        log.chunk(0, 0, 0, speech)
        log.partial("하나", false, 500)
        log.chunk(1, 100, 100, silence) // 100ms 쉼은 새 말 시작이 아니다
        log.chunk(2, 200, 200, speech)
        log.endOfSpeech()
        assertNull(log.partial("둘", false, 900).let { lines().last().latencyMs })
        log.endOfSpeech()
        repeat(3) { log.chunk(3L + it, 300L + it * 100, 300L + it * 100, silence) }
        log.chunk(6, 600, 600, speech)
        log.partial("셋", false, 1_400)
        assertEquals(800L, lines().last().latencyMs)
        assertEquals(CaptionLog.Stats(2, 650, 800), log.totalStats())
    }

    @Test
    fun resumingMidSpeechIsNotAnOnset() {
        log.clockOffsetMs = 0
        log.chunk(0, 0, 0, speech)
        log.interrupt(NoticeKind.PAUSED)
        log.chunk(51, 5_100, 5_100, speech) // 재개 직후 말 중간
        log.chunk(52, 5_200, 5_200, silence)
        log.chunk(53, 5_300, 5_300, silence)
        log.chunk(54, 5_400, 5_400, silence)
        log.chunk(55, 5_500, 5_500, speech)
        log.partial("안전", false, 6_200)
        assertEquals(700L, lines()[0].latencyMs)
    }

    @Test
    fun onsetThatNeverBecameALineGoesStale() {
        log.clockOffsetMs = 0
        log.chunk(0, 0, 0, speech)
        repeat(3) { log.chunk(1L + it, 100L + it * 100, 100L + it * 100, silence) }
        log.chunk(4, 400, 400, speech) // 1초 안: 같은 말로 본다
        repeat(25) { log.chunk(5L + it, 500L + it * 100, 500L + it * 100, silence) }
        log.chunk(30, 3_000, 3_000, speech) // 2초 넘게 줄이 없었다 → 새 말 시작
        log.partial("안전", false, 3_700)
        assertEquals(700L, lines()[0].latencyMs)
    }

    @Test
    fun withoutClockOnlyRecognizerLatencyIsKnown() {
        log.chunk(0, 50_000, 1_000, speech)
        log.partial("하나", false, 1_600)
        assertEquals(600L, lines()[0].latencyMs)
        assertEquals(0, log.totalStats().count)
        assertEquals(1, log.recognizerStats().count)
    }

    @Test
    fun clearForgetsEverything() {
        log.clockOffsetMs = 5
        log.chunk(0, 0, 0, speech)
        log.partial("하나", false, 10)
        log.mine("s1")
        log.clear()
        assertEquals(0, log.size())
        assertNull(log.clockOffsetMs)
        assertEquals(0, log.totalStats().count)
        assertEquals(1, log.partial("둘", false, 0))
    }

    @Test
    fun statsUseMedianAndMax() {
        assertEquals(CaptionLog.Stats(0, null, null), CaptionLog.stats(emptyList()))
        assertEquals(CaptionLog.Stats(3, 500, 900), CaptionLog.stats(listOf(900, 100, 500)))
        assertEquals(CaptionLog.Stats(4, 350, 900), CaptionLog.stats(listOf(900, 100, 500, 200)))
    }

    @Test
    fun rmsOfPcm16() {
        assertEquals(0, CaptionLog.rms(silence))
        assertEquals(0x1010, CaptionLog.rms(speech))
        assertEquals(0, CaptionLog.rms(ByteArray(0)))
    }
}
