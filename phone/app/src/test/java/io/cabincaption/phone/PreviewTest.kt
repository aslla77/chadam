package io.cabincaption.phone

import io.cabincaption.phone.CaptionLog.Entry
import io.cabincaption.phone.CaptionLog.NoticeKind
import io.cabincaption.phone.SayLog.Failure
import io.cabincaption.phone.SayLog.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTest {
    @Test
    fun releaseIgnoresEveryExtra() {
        Preview.Scene.entries.forEach { s ->
            ThemeId.entries.forEach { t -> assertNull(Preview.parse(false, s.key, t.key)) }
        }
        assertNull(Preview.parse(false, "talk", null))
        assertNull(Preview.parse(false, null, "kia"))
    }

    @Test
    fun debugParsesSceneAndTheme() {
        val r = Preview.parse(true, "busy", "sand")!!
        assertEquals(Preview.Scene.BUSY, r.scene)
        assertEquals("sand", r.theme)
        assertEquals(listOf("start", "consent", "empty", "talk", "busy", "silent", "ended", "opening"), Preview.Scene.entries.map { it.key })
    }

    @Test
    fun debugIgnoresNoOrUnknownExtras() {
        assertNull(Preview.parse(true, null, null))
        assertNull(Preview.parse(true, "chat", "blue"))
        val themeOnly = Preview.parse(true, null, "contrast")!!
        assertNull(themeOnly.scene)
        assertEquals("contrast", themeOnly.theme)
        assertEquals("system", Preview.parse(true, null, "system")!!.theme)
        assertNull(Preview.parse(false, "talk", "system"))
        assertNull(Preview.parse(true, "talk", "blue")!!.theme)
    }

    @Test
    fun talkSampleMatchesMockup() {
        val captions = CaptionLog()
        val says = SayLog()
        Preview.fill(Preview.Scene.TALK, captions, says)
        val es = captions.snapshot()
        assertEquals(5, es.size)
        assertEquals(Entry.Line(1, "점심은 도착해서 먹을까?", true, null), es[0])
        assertEquals("나는 국수 먹고 싶어", (es[1] as Entry.Shared).text)
        assertEquals(Entry.Line(2, "좋아, 그럼 국숫집 가자", true, null), es[2])
        assertEquals(Entry.Mine("s1"), es[3])
        assertEquals(Entry.Line(3, "30분쯤 뒤면 도착", false, null), es[4])
        assertEquals(SayLog.Item("s1", "저는 비빔국수요", Step.DONE), says.snapshot().single())
    }

    @Test
    fun busySampleMatchesMockup() {
        val captions = CaptionLog()
        val says = SayLog()
        Preview.fill(Preview.Scene.BUSY, captions, says)
        val es = captions.snapshot()
        assertEquals(listOf(Entry.Mine("s1")), es.take(1))
        assertEquals("아, 미안. 전화 끊었어. 뭐라고 했어?", (es[1] as Entry.Line).text)
        assertEquals(Entry.Notice(NoticeKind.NAV), es[2])
        assertEquals(Entry.Mine("s2"), es[3])
        val items = says.snapshot()
        assertEquals(SayLog.Item("s1", "물 좀 주세요", Step.FAILED, Failure.CALL), items[0])
        assertEquals(SayLog.Item("s2", "물 좀 주세요", Step.WAITING), items[1])
    }

    @Test
    fun silentSampleHasOneSentAndOneNobodyBubble() {
        val captions = CaptionLog()
        val says = SayLog()
        Preview.fill(Preview.Scene.SILENT, captions, says)
        assertEquals(listOf(Entry.Mine("s1"), Entry.Mine("s2")), captions.snapshot())
        val items = says.snapshot()
        assertEquals(SayLog.Item("s1", "뒷자리 창문 좀 닫아 줄래?", Step.DONE, silent = true), items[0])
        assertEquals(SayLog.Item("s2", "곧 휴게소 들른대", Step.FAILED, Failure.NOBODY, silent = true), items[1])
    }

    @Test
    fun nonConversationScenesAddNothing() {
        listOf(Preview.Scene.START, Preview.Scene.CONSENT, Preview.Scene.EMPTY, Preview.Scene.ENDED, Preview.Scene.OPENING).forEach {
            val captions = CaptionLog()
            val says = SayLog()
            Preview.fill(it, captions, says)
            assertTrue(captions.snapshot().isEmpty())
            assertTrue(says.snapshot().isEmpty())
        }
    }
}
