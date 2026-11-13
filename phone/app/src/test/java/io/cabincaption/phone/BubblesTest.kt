package io.cabincaption.phone

import io.cabincaption.phone.Bubbles.Side
import io.cabincaption.phone.CaptionLog.Entry
import io.cabincaption.phone.CaptionLog.NoticeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblesTest {
    private val t = Bubbles.Templates("차 자막: %1\$s", "차 자막, 듣는 중: %1\$s", "내가 보낸 글: %1\$s, 상태 %2\$s", "%1\$s 보낸 글: %2\$s")

    @Test
    fun sides() {
        assertEquals(Side.LEFT, Bubbles.side(Entry.Line(1, "a", true, null)))
        assertEquals(Side.RIGHT, Bubbles.side(Entry.Mine("s1")))
        assertEquals(Side.LEFT, Bubbles.side(Entry.Shared(1, "1", "b")))
        assertEquals(Side.CENTER, Bubbles.side(Entry.Notice(NoticeKind.PAUSED)))
    }

    @Test
    fun descriptionsNameTheSpeaker() {
        assertEquals("차 자막: 안녕", Bubbles.carDescription(Entry.Line(1, "안녕", true, null), t))
        assertEquals("차 자막, 듣는 중: 안녕", Bubbles.carDescription(Entry.Line(1, "안녕", false, null), t))
        assertEquals("내가 보낸 글: 네, 상태 완료", Bubbles.mineDescription("네", "완료", t))
        assertEquals("내가 보낸 글: 네, 상태 실패: 문장이 너무 깁니다", Bubbles.mineDescription("네", "실패: 문장이 너무 깁니다", t))
        assertEquals("동승자 보낸 글: 창문 열어요", Bubbles.sharedDescription(Entry.Shared(1, "7", "창문 열어요"), "동승자", t))
        assertEquals("지안 보낸 글: 창문 열어요", Bubbles.sharedDescription(Entry.Shared(1, "7", "창문 열어요", "지안"), "동승자", t))
        assertEquals("지안", Bubbles.sharedLabel(Entry.Shared(1, "7", "x", "지안"), "동승자"))
        assertEquals("동승자", Bubbles.sharedLabel(Entry.Shared(1, "7", "x"), "동승자"))
    }

    @Test
    fun followsBottomOnlyNearBottom() {
        assertTrue(Bubbles.followsBottom(0, 500, 300, 80))
        assertTrue(Bubbles.followsBottom(500, 500, 1000, 80))
        assertTrue(Bubbles.followsBottom(430, 500, 1000, 80))
        assertFalse(Bubbles.followsBottom(100, 500, 1000, 80))
        assertFalse(Bubbles.followsBottom(419, 500, 1000, 80))
    }

    @Test
    fun maxWidthIs78Percent() {
        assertEquals(780, Bubbles.maxBubbleWidth(1000))
    }

    @Test
    fun announcesOnlyNewFinalLines() {
        val es = listOf(
            Entry.Line(1, "a", true, null),
            Entry.Mine("s1"),
            Entry.Line(2, "b", true, null),
            Entry.Line(3, "c", false, null),
        )
        assertEquals(listOf(1, 2), Bubbles.newlyFinal(es, 0).map { it.no })
        assertEquals(listOf(2), Bubbles.newlyFinal(es, 1).map { it.no })
        assertTrue(Bubbles.newlyFinal(es, 2).isEmpty())
    }

    @Test
    fun announcesOnlyNewSharedTextApartFromCarLines() {
        val es = listOf(
            Entry.Line(1, "a", true, null),
            Entry.Shared(1, "1", "x"),
            Entry.Line(2, "b", false, null),
            Entry.Shared(2, "2", "y"),
        )
        assertEquals(listOf("x", "y"), Bubbles.newlyShared(es, 0).map { it.text })
        assertEquals(listOf("y"), Bubbles.newlyShared(es, 1).map { it.text })
        assertTrue(Bubbles.newlyShared(es, 2).isEmpty())
        assertEquals(listOf(1), Bubbles.newlyFinal(es, 0).map { it.no })
    }

    @Test
    fun rebuildKeepsBottomOrExactScrollPosition() {
        assertEquals(null, Bubbles.restoredScrollY(scrollY = 1_000, viewportH = 500, contentH = 1_500, thresholdPx = 80))
        assertEquals(null, Bubbles.restoredScrollY(scrollY = 930, viewportH = 500, contentH = 1_500, thresholdPx = 80))
        assertEquals(null, Bubbles.restoredScrollY(scrollY = 0, viewportH = 500, contentH = 300, thresholdPx = 80))
        assertEquals(900, Bubbles.restoredScrollY(scrollY = 900, viewportH = 500, contentH = 1_500, thresholdPx = 80))
        assertEquals(0, Bubbles.restoredScrollY(scrollY = 0, viewportH = 500, contentH = 1_500, thresholdPx = 80))
    }
}
