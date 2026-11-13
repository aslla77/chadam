package io.cabincaption.phone

import io.cabincaption.phone.SayLog.Failure
import io.cabincaption.phone.SayLog.Step
import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SayLogTest {
    private val log = SayLog()

    private fun step(id: String) = log.snapshot().first { it.id == id }.step

    @Test
    fun normalFlow() {
        log.add("s1", "안녕")
        assertEquals(Step.MAKING, step("s1"))
        log.sent("s1")
        log.car("s1", SayState.QUEUED)
        log.car("s1", SayState.PLAYING)
        assertEquals(Step.PLAYING, step("s1"))
        log.car("s1", SayState.DONE)
        assertEquals(Step.DONE, step("s1"))
    }

    @Test
    fun outOfOrderCarStatesNeverGoBack() {
        log.add("s1", "a")
        log.car("s1", SayState.PLAYING)
        log.car("s1", SayState.QUEUED)
        log.sent("s1")
        assertEquals(Step.PLAYING, step("s1"))
        log.car("s1", SayState.DONE)
        log.car("s1", SayState.FAILED)
        assertEquals(Step.DONE, step("s1"))
    }

    @Test
    fun failuresAreFinal() {
        log.add("s1", "a")
        log.failed("s1", Failure.SYNTH)
        log.car("s1", SayState.DONE)
        assertEquals(Failure.SYNTH, log.snapshot()[0].failure)
        log.add("s2", "b")
        log.sent("s2")
        log.car("s2", SayState.FAILED)
        assertEquals(Failure.CAR, log.snapshot()[1].failure)
    }

    @Test
    fun disconnectMarksInFlightAsLostButNotMakingOrDone() {
        listOf("m", "s", "p", "d").forEach { log.add(it, it) }
        log.sent("s")
        log.car("p", SayState.PLAYING)
        log.car("d", SayState.DONE)
        log.disconnected()
        assertEquals(Step.MAKING, step("m"))
        assertEquals(Failure.LOST, log.snapshot().first { it.id == "s" }.failure)
        assertEquals(Failure.LOST, log.snapshot().first { it.id == "p" }.failure)
        assertEquals(Step.DONE, step("d"))
    }

    @Test
    fun unknownIdIgnoredAndClearEmpties() {
        log.car("nope", SayState.DONE)
        log.add("s1", "a")
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun navWaitThenPlayThenWaitAgainThenDone() {
        log.add("s1", "a")
        log.sent("s1")
        log.car("s1", SayState.WAITING, Wire.SAID_NAV)
        assertEquals(Step.WAITING, step("s1"))
        log.car("s1", SayState.QUEUED)
        assertEquals(Step.WAITING, step("s1"))
        log.car("s1", SayState.PLAYING)
        assertEquals(Step.PLAYING, step("s1"))
        log.car("s1", SayState.WAITING, Wire.SAID_NAV)
        assertEquals(Step.WAITING, step("s1"))
        log.car("s1", SayState.PLAYING)
        log.car("s1", SayState.DONE)
        assertEquals(Step.DONE, step("s1"))
        log.car("s1", SayState.WAITING, Wire.SAID_NAV)
        assertEquals(Step.DONE, step("s1"))
    }

    @Test
    fun carFailureReasonsMapToFailures() {
        listOf("c" to Wire.SAID_CALL, "n" to Wire.SAID_NAV, "x" to "focus", "y" to null).forEach { (id, reason) ->
            log.add(id, id)
            log.sent(id)
            log.car(id, SayState.FAILED, reason)
        }
        assertEquals(listOf(Failure.CALL, Failure.NAV, Failure.CAR, Failure.CAR), log.snapshot().map { it.failure })
    }

    @Test
    fun saysAreNotSilentByDefault() {
        log.add("s1", "a")
        assertFalse(log.snapshot()[0].silent)
    }

    @Test
    fun silentNoteGoesPendingThenDone() {
        log.add("n1", "뒷자리에만", silent = true)
        assertEquals(SayLog.Item("n1", "뒷자리에만", Step.MAKING, silent = true), log.snapshot()[0])
        log.sent("n1")
        assertEquals(Step.SENT, step("n1"))
        log.car("n1", SayState.DONE)
        assertEquals(SayLog.Item("n1", "뒷자리에만", Step.DONE, silent = true), log.snapshot()[0])
    }

    @Test
    fun silentNoteDoneBeforeSentStaysDone() {
        log.add("n1", "a", silent = true)
        log.car("n1", SayState.DONE)
        log.sent("n1")
        assertEquals(Step.DONE, step("n1"))
    }

    @Test
    fun silentNoteWithNobodyFails() {
        log.add("n1", "a", silent = true)
        log.sent("n1")
        log.car("n1", SayState.FAILED, Wire.SAID_NOBODY)
        assertEquals(SayLog.Item("n1", "a", Step.FAILED, Failure.NOBODY, silent = true), log.snapshot()[0])
    }

    @Test
    fun silentNoteFailuresFollowTheSayRules() {
        listOf("sent", "off", "format", "done").forEach { log.add(it, it, silent = true) }
        log.sent("sent")
        log.failed("off", Failure.NOT_CONNECTED)
        log.sent("format")
        log.car("format", SayState.FAILED, "format")
        log.car("done", SayState.DONE)
        log.disconnected()
        val byId = log.snapshot().associateBy { it.id }
        assertEquals(Failure.LOST, byId["sent"]!!.failure)
        assertEquals(Failure.NOT_CONNECTED, byId["off"]!!.failure)
        assertEquals(Failure.CAR, byId["format"]!!.failure)
        assertEquals(Step.DONE, byId["done"]!!.step)
        assertTrue(byId.values.all { it.silent })
    }

    @Test
    fun disconnectWhileWaitingIsLost() {
        log.add("s1", "a")
        log.car("s1", SayState.WAITING, Wire.SAID_NAV)
        log.disconnected()
        assertEquals(Failure.LOST, log.snapshot()[0].failure)
    }
}
