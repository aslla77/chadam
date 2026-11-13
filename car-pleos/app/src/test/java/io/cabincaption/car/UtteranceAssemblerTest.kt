package io.cabincaption.car

import io.cabincaption.car.UtteranceAssembler.Result
import io.cabincaption.protocol.Wire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceAssemblerTest {

    private val a = UtteranceAssembler()

    private fun start(id: String, rate: String = "24000") = a.start(mapOf("t" to "say", "id" to id, "rate" to rate))

    private fun end(id: String) = a.end(mapOf("t" to "say_end", "id" to id))

    @Test
    fun assemblesChunksInOrder() {
        assertSame(Result.Pending, start("u1"))
        assertSame(Result.Pending, a.pcm(byteArrayOf(1, 2)))
        assertSame(Result.Pending, a.pcm(byteArrayOf(3, 4)))
        val r = end("u1") as Result.Ready
        assertEquals("u1", r.utterance.id)
        assertEquals(24_000, r.utterance.rate)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), r.utterance.pcm)
        assertSame(Result.Ignored, a.pcm(byteArrayOf(5, 6)))
    }

    @Test
    fun validTextIsKeptAndBadTextIsDroppedButStillPlays() {
        fun ready(fields: Map<String, String>): Utterance {
            a.start(mapOf("t" to "say", "id" to "u1", "rate" to "24000") + fields)
            a.pcm(byteArrayOf(1, 2))
            return (end("u1") as Result.Ready).utterance
        }
        assertEquals("에어컨 줄여 주세요", ready(mapOf("text" to "에어컨 줄여 주세요")).text)
        assertEquals("가".repeat(100), ready(mapOf("text" to "가".repeat(100))).text)
        assertNull(ready(emptyMap()).text)
        assertNull(ready(mapOf("text" to "")).text)
        assertNull(ready(mapOf("text" to "  \n ")).text)
        assertNull(ready(mapOf("text" to "가".repeat(101))).text)
    }

    @Test
    fun textDoesNotLeakIntoTheNextUtterance() {
        a.start(mapOf("t" to "say", "id" to "u1", "rate" to "24000", "text" to "첫 글"))
        a.pcm(byteArrayOf(1, 2))
        end("u1")
        start("u2")
        a.pcm(byteArrayOf(1, 2))
        assertNull((end("u2") as Result.Ready).utterance.text)
    }

    @Test
    fun pcmWithoutStartAndUnmatchedEndAreIgnored() {
        assertSame(Result.Ignored, a.pcm(byteArrayOf(1, 2)))
        assertSame(Result.Ignored, end("x"))
        start("u1")
        assertSame(Result.Ignored, end("other"))
        a.pcm(byteArrayOf(1, 2))
        assertTrue(end("u1") is Result.Ready)
    }

    @Test
    fun badRateFailsAndBadIdIsIgnored() {
        assertEquals(Result.Failed("u1", "format"), start("u1", "7000"))
        assertEquals(Result.Failed("u1", "format"), start("u1", "abc"))
        assertSame(Result.Ignored, start("has space"))
        assertSame(Result.Ignored, a.pcm(byteArrayOf(1, 2)))
    }

    @Test
    fun emptyAndOddLengthFail() {
        start("u1")
        assertEquals(Result.Failed("u1", "empty"), end("u1"))
        start("u2")
        a.pcm(byteArrayOf(1, 2, 3))
        assertEquals(Result.Failed("u2", "format"), end("u2"))
    }

    @Test
    fun overLongUtteranceIsDroppedOnce() {
        start("u1", "8000")
        val max = Wire.sayMaxBytes(8_000)
        assertSame(Result.Pending, a.pcm(ByteArray(max)))
        assertEquals(Result.Failed("u1", "too_long"), a.pcm(ByteArray(2)))
        assertSame(Result.Ignored, a.pcm(ByteArray(2)))
        assertSame(Result.Ignored, end("u1"))
    }

    @Test
    fun newStartInterruptsUnfinishedOne() {
        start("u1")
        a.pcm(byteArrayOf(1, 2))
        assertEquals(Result.Failed("u1", "interrupted"), start("u2"))
        a.pcm(byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(9, 9), (end("u2") as Result.Ready).utterance.pcm)
    }
}
