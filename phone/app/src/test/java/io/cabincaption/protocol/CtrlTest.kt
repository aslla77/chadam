package io.cabincaption.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CtrlTest {

    private fun decode(s: String) = Ctrl.decode(s.toByteArray(Charsets.UTF_8))

    @Test
    fun roundTripsEscapesAndKorean() {
        val m = linkedMapOf("t" to "welcome", "msg" to "차 \"마이크\"\\ \n\t\r\u0001 끝", "empty" to "")
        assertEquals(m, Ctrl.decode(Ctrl.encode(m)))
    }

    @Test
    fun encodesPlainJson() {
        assertEquals("{\"t\":\"reject\",\"reason\":\"full\"}", String(Ctrl.encode(linkedMapOf("t" to "reject", "reason" to "full"))))
        assertEquals("{}", String(Ctrl.encode(emptyMap())))
    }

    @Test
    fun acceptsWhitespaceAndStandardEscapes() {
        assertEquals(mapOf("a" to "/\b\u000C\u00e9"), decode(" { \"a\" : \"\\/\\b\\f\\u00E9\" } "))
        assertEquals(emptyMap<String, String>(), decode("{ }"))
    }

    @Test
    fun rejectsMalformedBodies() {
        listOf(
            "",
            "[]",
            "{\"a\":1}",
            "{\"a\":\"b\"",
            "{\"a\":\"b\",}",
            "{\"a\":\"b\"} x",
            "{\"a\":\"b\",\"a\":\"c\"}",
            "{\"a\":\"\\x\"}",
            "{\"a\":\"\\u12\"}",
            "{\"a\":\"\\u-001\"}",
            "{\"a\":\"line\nbreak\"}",
            "{a:\"b\"}",
        ).forEach { s -> assertThrows(s, ProtocolException::class.java) { decode(s) } }
    }

    @Test
    fun wireHelpersBuildFrames() {
        val f = Wire.ctrl(Wire.TYPE to Wire.REJECT, Wire.REASON to RejectReason.DRIVING.wire)
        assertEquals(FrameType.CTRL, f.type)
        assertEquals(mapOf("t" to "reject", "reason" to "driving"), Ctrl.decode(f.body))
        val h = Wire.hello(mapOf(Wire.TOKEN to "abc"))
        assertEquals(FrameType.HELLO, h.type)
        assertEquals(mapOf("token" to "abc"), Ctrl.decode(h.body))
    }
}
