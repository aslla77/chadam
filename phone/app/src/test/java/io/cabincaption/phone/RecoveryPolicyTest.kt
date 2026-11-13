package io.cabincaption.phone

import io.cabincaption.phone.RecoveryPolicy.Action
import io.cabincaption.phone.RecoveryPolicy.Kind
import io.cabincaption.phone.RecoveryPolicy.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {

    private fun d(error: Int, n: Int = 1) = RecoveryPolicy.decide(error, n)

    @Test
    fun normalEndsRestartAtOnceWithoutNotice() {
        assertEquals(Action(Kind.RESTART), d(6))
        assertEquals(Action(Kind.RESTART), d(7, 5))
        assertTrue(RecoveryPolicy.isNormal(6))
        assertFalse(RecoveryPolicy.isNormal(5))
    }

    @Test
    fun busyWaitsBriefly() = assertEquals(Action(Kind.RESTART, 300), d(8))

    @Test
    fun tooManyRequestsBacksOffExponentiallyUpToEightSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), (1..5).map { d(10, it).delayMs })
        assertEquals(Notice.SLOW, d(10).notice)
    }

    @Test
    fun permissionStops() = assertEquals(Action(Kind.STOP, notice = Notice.NEED_PERMISSION), d(9))

    // B-1: 기기 안 인식을 못 쓰면 서버일 수 있는 기본 인식기로 넘어가지 않고 멈춘다
    @Test
    fun languageProblemsStopInsteadOfFallingBackToServer() {
        assertEquals(Action(Kind.STOP, notice = Notice.NEED_LANGUAGE), d(13))
        assertEquals(Action(Kind.STOP, notice = Notice.NO_ON_DEVICE), d(12))
        assertEquals(Action(Kind.STOP, notice = Notice.NO_ON_DEVICE), d(12, 3))
    }

    @Test
    fun startNeedsPermissionAndOnDeviceRecognizer() {
        assertEquals(null, RecoveryPolicy.startNotice(permitted = true, onDeviceAvailable = true))
        assertEquals(Notice.NO_ON_DEVICE, RecoveryPolicy.startNotice(permitted = true, onDeviceAvailable = false))
        assertEquals(Notice.NEED_PERMISSION, RecoveryPolicy.startNotice(permitted = false, onDeviceAvailable = true))
        assertEquals(Notice.NEED_PERMISSION, RecoveryPolicy.startNotice(permitted = false, onDeviceAvailable = false))
    }

    @Test
    fun noErrorLeadsToAnotherRecognizer() {
        assertEquals(listOf("RESTART", "RECREATE", "STOP"), Kind.entries.map { it.name })
    }

    @Test
    fun serverDisconnectRecreatesThenPausesAfterThree() {
        assertEquals(Action(Kind.RECREATE), d(11, 2))
        assertEquals(Action(Kind.RECREATE, 5_000, Notice.UNAVAILABLE), d(11, 3))
    }

    @Test
    fun networkErrorsBackOffWithUnstableNotice() {
        for (e in listOf(1, 2, 4)) assertEquals(Action(Kind.RESTART, 2_000, Notice.UNSTABLE), d(e, 2))
    }

    @Test
    fun audioAndClientErrorsRestartThenPauseAfterThree() {
        assertEquals(Action(Kind.RESTART), d(3, 1))
        assertEquals(Action(Kind.RESTART), d(5, 2))
        assertEquals(Action(Kind.RESTART, 5_000, Notice.UNAVAILABLE), d(5, 3))
        assertEquals(Action(Kind.RESTART, 5_000, Notice.UNAVAILABLE), d(99, 3))
    }
}
