package io.cabincaption.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    @Test
    fun sayMessagesRoundTrip() {
        assertEquals(mapOf("t" to "say", "id" to "a1", "rate" to "24000"), Ctrl.decode(Wire.say("a1", 24_000).body))
        assertEquals(mapOf("t" to "say_end", "id" to "a1"), Ctrl.decode(Wire.sayEnd("a1").body))
        assertEquals(mapOf("t" to "said", "id" to "a1", "state" to "playing"), Ctrl.decode(Wire.said("a1", SayState.PLAYING).body))
        assertEquals(
            mapOf("t" to "said", "id" to "a1", "state" to "failed", "reason" to "busy"),
            Ctrl.decode(Wire.said("a1", SayState.FAILED, "busy").body),
        )
        assertEquals(
            mapOf("t" to "said", "id" to "a1", "state" to "waiting", "reason" to "nav"),
            Ctrl.decode(Wire.said("a1", SayState.WAITING, Wire.SAID_NAV).body),
        )
        assertEquals("call", Ctrl.decode(Wire.said("a1", SayState.FAILED, Wire.SAID_CALL).body)["reason"])
        assertEquals(mapOf("t" to "speaker", "on" to "1"), Ctrl.decode(Wire.speaker(true).body))
        assertEquals(mapOf("t" to "speaker", "on" to "0"), Ctrl.decode(Wire.speaker(false).body))
        assertEquals(FrameType.PHONE_PCM, Wire.pcm(ByteArray(2)).type)
    }

    @Test
    fun sayCarriesOptionalTextAndSharedRoundTrips() {
        assertEquals(
            mapOf("t" to "say", "id" to "a1", "rate" to "24000", "text" to "창문 \"조금\"\n열어 주세요"),
            Ctrl.decode(Wire.say("a1", 24_000, "창문 \"조금\"\n열어 주세요").body),
        )
        assertFalse(Ctrl.decode(Wire.say("a1", 24_000).body).containsKey("text"))
        assertEquals(mapOf("t" to "shared", "id" to "7", "text" to "네 좋아요"), Ctrl.decode(Wire.shared("7", "네 좋아요").body))
        assertEquals(mapOf("t" to "shared", "id" to "7", "text" to "네", "name" to "지안"), Ctrl.decode(Wire.shared("7", "네", "지안").body))
        assertTrue(Wire.validName("지안"))
        assertTrue(Wire.validName("가".repeat(Wire.NAME_MAX_CHARS)))
        for (bad in listOf(null, "", " ", " 지안", "지안 ", "지\n안", "가".repeat(Wire.NAME_MAX_CHARS + 1))) assertFalse("$bad", Wire.validName(bad))
    }

    @Test
    fun noteRoundTripsAndNobodyIsAFailureReason() {
        assertEquals(mapOf("t" to "note", "id" to "s9", "text" to "뒷자리 \"창문\"\n닫아 줘"), Ctrl.decode(Wire.note("s9", "뒷자리 \"창문\"\n닫아 줘").body))
        assertEquals(
            mapOf("t" to "said", "id" to "s9", "state" to "failed", "reason" to "nobody"),
            Ctrl.decode(Wire.said("s9", SayState.FAILED, Wire.SAID_NOBODY).body),
        )
        assertTrue(Wire.note("9".repeat(32), "\u0001".repeat(Wire.SAY_TEXT_MAX_BYTES)).body.size <= FrameCodec.MAX_BODY)
    }

    @Test
    fun textLimitCoversTheWholePhoneInputInUtf8() {
        assertEquals(300, Wire.SAY_TEXT_MAX_BYTES)
        assertTrue(Wire.validText("가".repeat(Wire.SAY_TEXT_MAX_CHARS)))
        assertTrue(Wire.validText("😀".repeat(Wire.SAY_TEXT_MAX_CHARS / 2)))
        assertTrue(Wire.validText("a"))
        assertFalse(Wire.validText("가".repeat(Wire.SAY_TEXT_MAX_CHARS) + "a"))
        assertFalse(Wire.validText(""))
        assertFalse(Wire.validText(" \n\t"))
        assertFalse(Wire.validText(null))
        assertTrue(Wire.ctrl(Wire.TYPE to Wire.SHARED, Wire.ID to "9".repeat(32), Wire.TEXT to "\u0001".repeat(Wire.SAY_TEXT_MAX_BYTES)).body.size <= FrameCodec.MAX_BODY)
    }

    @Test
    fun captionControlMessagesRoundTrip() {
        assertEquals(mapOf("t" to "pause", "on" to "1"), Ctrl.decode(Wire.pause(true).body))
        assertEquals(mapOf("t" to "pause", "on" to "0"), Ctrl.decode(Wire.pause(false).body))
        assertEquals(
            mapOf("t" to "mic", "silenced" to "1", "input" to "1"),
            Ctrl.decode(Wire.mic(true, true).body),
        )
        assertEquals("0", Ctrl.decode(Wire.mic(false, false).body)["input"])
        assertEquals(mapOf("t" to "nav", "on" to "1"), Ctrl.decode(Wire.nav(true).body))
        assertEquals(mapOf("t" to "nav", "on" to "0"), Ctrl.decode(Wire.nav(false).body))
        assertEquals(mapOf("t" to "clock"), Ctrl.decode(Wire.clockPing().body))
        assertEquals(mapOf("t" to "clock", "now" to "1730000000123"), Ctrl.decode(Wire.clockPong(1_730_000_000_123).body))
    }

    @Test
    fun heartbeatIsAPingWithTimeoutOfThreeMissedPings() {
        assertEquals(mapOf("t" to "ping"), Ctrl.decode(Wire.ping().body))
        assertEquals(3 * Wire.PING_MS, Wire.PEER_TIMEOUT_MS.toLong())
    }

    @Test
    fun flagsAcceptOnlyZeroOrOne() {
        assertEquals(true, Wire.parseFlag("1"))
        assertEquals(false, Wire.parseFlag("0"))
        assertNull(Wire.parseFlag("true"))
        assertNull(Wire.parseFlag(""))
        assertNull(Wire.parseFlag(null))
    }

    @Test
    fun enumsParseWireNamesOnly() {
        assertEquals(SayState.DONE, SayState.of("done"))
        assertEquals(SayState.WAITING, SayState.of("waiting"))
        assertNull(SayState.of("DONE"))
        assertNull(SayState.of(null))
        assertEquals(RejectReason.DRIVING, RejectReason.of("driving"))
        assertNull(RejectReason.of("other"))
    }

    @Test
    fun limits() {
        assertEquals(24_000 * 2 * 25, Wire.sayMaxBytes(24_000))
        assertTrue(Wire.validRate(24_000))
        assertFalse(Wire.validRate(7_999))
        assertFalse(Wire.validRate(48_001))
        assertFalse(Wire.validRate(null))
        assertTrue(Wire.PCM_CHUNK % 2 == 0)
        assertTrue(Wire.PCM_CHUNK <= FrameCodec.MAX_BODY)
    }

    @Test
    fun idsAreShortUrlSafe() {
        assertTrue(Wire.validId("p1-abc_9"))
        assertFalse(Wire.validId(""))
        assertFalse(Wire.validId(null))
        assertFalse(Wire.validId("a".repeat(33)))
        assertFalse(Wire.validId("a b"))
        assertFalse(Wire.validId("한"))
    }
}
