package io.cabincaption.car

import io.cabincaption.car.SessionController.Mic
import io.cabincaption.car.SessionController.Phase
import io.cabincaption.car.SessionController.Problem
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    @Test
    fun autoOpensOnlyWhenParked() {
        for (d in DrivingState.values()) {
            assertEquals("$d", d == DrivingState.PARKED, SessionController.canAutoOpen(d))
        }
    }

    @Test
    fun autoOpenWaitShowsDrivingAtOnceAndUnknownAfterGrace() {
        val grace = SessionController.UNKNOWN_GRACE_MS
        assertEquals(Problem.DRIVING, SessionController.autoOpenWait(DrivingState.DRIVING, 0))
        assertNull(SessionController.autoOpenWait(DrivingState.UNKNOWN, grace - 1))
        assertEquals(Problem.DRIVING_UNKNOWN, SessionController.autoOpenWait(DrivingState.UNKNOWN, grace))
        assertNull(SessionController.autoOpenWait(DrivingState.PARKED, grace))
    }

    @Test
    fun settingsEditableOnlyWhenParkedAndOff() {
        for (d in DrivingState.values()) for (p in Phase.values()) {
            assertEquals("$d $p", d == DrivingState.PARKED && p == Phase.OFF, SessionController.canEditSettings(d, p))
        }
    }

    private class FakeServer(val failStart: Boolean = false) : SessionServer {
        var started = 0
        var stopped = 0
        var phones = 0
        var busy = false
        var paused = false
        var silenced = false
        var input: Boolean? = null
        var boundTo: String? = null
        val chunks = mutableListOf<Long>()
        override fun start(port: Int, bindHost: String): Int {
            if (failStart) throw IOException("in use")
            started++
            boundTo = bindHost
            return port
        }
        override fun pin() = "P".repeat(43)
        override fun stop() {
            stopped++
        }
        override fun phones() = phones
        override fun token(now: Long) = "tok$now"
        override fun speakerBusy() = busy
        override fun carPcm(pcm: ByteArray, captureMs: Long) {
            chunks += captureMs
        }
        override fun setMicSilenced(on: Boolean) {
            silenced = on
        }
        override fun setMicInput(on: Boolean) {
            input = on
        }
        override fun paused() = paused
        override fun micSilenced() = silenced
        var navOn = false
        override fun setNav(on: Boolean) {
            navOn = on
        }
        override fun nav() = navOn
        var list = listOf<PhoneEntry>()
        val kicked = mutableListOf<Long>()
        override fun phoneList() = list
        override fun kick(member: Long): Boolean {
            kicked += member
            return true
        }
    }

    private class FakeEffects(val micOk: Boolean = true) : SessionController.Effects {
        var chimes = 0
        var micStarts = 0
        var micStops = 0
        var onStartMic: () -> Unit = {}
        override fun chime() {
            chimes++
        }
        override fun startMic(): Boolean {
            micStarts++
            onStartMic()
            return micOk
        }
        override fun stopMic() {
            micStops++
        }
    }

    private val target = JoinTarget("192.168.0.10", 47100)
    private val servers = mutableListOf<FakeServer>()
    private val effects = FakeEffects()
    private val pending = ArrayDeque<() -> Unit>()
    private val stops = ArrayDeque<() -> Unit>()
    private var now = 0L

    /** [separateStop]이면 서버 정리는 [stops]에 따로 쌓인다(실기기 CarRuntime처럼 켜기 실행기와 분리). */
    private fun controller(
        failStart: Boolean = false, fx: FakeEffects = effects, separateStop: Boolean = false, localHosts: List<String> = emptyList(),
    ) = SessionController(
        newServer = { FakeServer(failStart).also { servers += it } },
        effects = fx,
        clock = { 7 },
        runAsync = { pending += it },
        runStop = if (separateStop) ({ stops += it }) else ({ pending += it }),
        elapsed = { now },
        localHosts = { localHosts },
    )

    private fun drain() {
        while (pending.isNotEmpty()) pending.removeFirst()()
    }

    @Test
    fun startsUnknownAndRefusesToTurnOn() {
        val c = controller()
        assertFalse(c.turnOn(target, true))
        assertEquals(Problem.DRIVING_UNKNOWN, c.snapshot().problem)
        assertTrue(servers.isEmpty())
    }

    @Test
    fun drivingRefusesTurnOn() {
        val c = controller()
        c.driving = DrivingState.DRIVING
        assertFalse(c.turnOn(target, true))
        val s = c.snapshot()
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Problem.DRIVING, s.problem)
        assertTrue(servers.isEmpty())
        assertEquals(0, effects.chimes)
    }

    @Test
    fun parkedTurnsOnWithQrMicAndOneChime() {
        val c = controller()
        c.driving = DrivingState.PARKED
        assertTrue(c.turnOn(target, true))
        assertEquals(Phase.STARTING, c.snapshot().phase)
        drain()
        val s = c.snapshot()
        assertEquals(Phase.ON, s.phase)
        assertEquals("cabincaption://join2?h=192.168.0.10&p=47100&t=tok7&f=${"P".repeat(43)}", s.qrText)
        assertEquals(Mic.ON, s.mic)
        assertNull(s.problem)
        assertEquals(1, effects.chimes)
        assertFalse(c.turnOn(target, true))
        assertEquals(1, servers.size)
    }

    // T8: QR 주소가 이 기기 주소면 거기에만, 아니면(에뮬 + Mac 전달기) 루프백에만 연다
    @Test
    fun serverBindsQrHostOnlyWhenItIsLocalOtherwiseLoopback() {
        val local = controller(localHosts = listOf("10.0.2.15", "192.168.0.10"))
        local.driving = DrivingState.PARKED
        local.turnOn(target, true)
        drain()
        assertEquals("192.168.0.10", servers.last().boundTo)
        val emu = controller(localHosts = listOf("10.0.2.15"))
        emu.driving = DrivingState.PARKED
        emu.turnOn(target, true)
        drain()
        assertEquals("127.0.0.1", servers.last().boundTo)
    }

    // T3: 목록과 내보내기는 켜져 있을 때만 서버로 간다
    @Test
    fun phoneListAndKickOnlyWhileOn() {
        val c = controller()
        c.driving = DrivingState.PARKED
        assertFalse(c.kick(1))
        c.turnOn(target, true)
        assertFalse(c.kick(1))
        drain()
        servers[0].list = listOf(PhoneEntry(1, "엄마"), PhoneEntry(3, null))
        assertEquals(servers[0].list, c.snapshot().phoneList)
        assertTrue(c.kick(3))
        assertEquals(listOf(3L), servers[0].kicked)
        c.turnOff()
        assertTrue(c.snapshot().phoneList.isEmpty())
        assertFalse(c.kick(1))
        assertEquals(listOf(3L), servers[0].kicked)
    }

    @Test
    fun keepsRunningWhenDrivingStartsAfterTurnOn() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        c.driving = DrivingState.DRIVING
        assertEquals(Phase.ON, c.snapshot().phase)
        assertEquals(0, servers[0].stopped)
    }

    @Test
    fun missingHostRefuses() {
        val c = controller()
        c.driving = DrivingState.PARKED
        assertFalse(c.turnOn(null, true))
        assertEquals(Problem.NO_HOST, c.snapshot().problem)
    }

    @Test
    fun portFailureReturnsToOffWithoutChimeOrMic() {
        val c = controller(failStart = true)
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        val s = c.snapshot()
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Problem.PORT_FAILED, s.problem)
        assertEquals(0, effects.chimes)
        assertEquals(0, effects.micStarts)
    }

    @Test
    fun noMicPermissionStillTurnsOn() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, micPermitted = false)
        drain()
        assertEquals(Mic.NO_PERMISSION, c.snapshot().mic)
        assertEquals(0, effects.micStarts)
    }

    @Test
    fun turnOffStopsServerAndMicAndClearsState() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        servers[0].phones = 2
        assertEquals(2, c.snapshot().phones)
        c.turnOff()
        drain()
        val s = c.snapshot()
        assertEquals(Phase.OFF, s.phase)
        assertEquals(0, s.phones)
        assertNull(s.qrText)
        assertEquals(1, servers[0].stopped)
        assertEquals(1, effects.micStops)
    }

    @Test
    fun turnOffWhileStartingStopsTheServerAndSkipsChime() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        c.turnOff()
        drain()
        assertEquals(Phase.OFF, c.snapshot().phase)
        assertEquals(1, servers[0].stopped)
        assertEquals(0, effects.chimes)
        assertEquals(1, effects.micStops)
    }

    @Test
    fun speakerBusyShowsOnlyWhileOn() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        assertFalse(c.snapshot().speakerBusy)
        servers[0].busy = true
        assertTrue(c.snapshot().speakerBusy)
        c.turnOff()
        assertFalse(c.snapshot().speakerBusy)
    }

    @Test
    fun carAudioReachesServerOnlyWhileOn() {
        val c = controller()
        c.carPcm(ByteArray(2), 1)
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        c.carPcm(ByteArray(2), 2)
        drain()
        c.carPcm(ByteArray(2), 3)
        c.setMicSilenced(true)
        assertEquals(listOf(3L), servers[0].chunks)
        assertTrue(c.snapshot().micSilenced)
        servers[0].paused = true
        assertTrue(c.snapshot().paused)
        c.turnOff()
        c.carPcm(ByteArray(2), 4)
        assertEquals(listOf(3L), servers[0].chunks)
        val s = c.snapshot()
        assertFalse(s.paused)
        assertFalse(s.micSilenced)
    }

    @Test
    fun eachSessionGetsAFreshServer() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        c.turnOff()
        drain()
        c.turnOn(target, true)
        drain()
        assertEquals(2, servers.size)
        assertEquals(2, effects.chimes)
    }

    // H-2: 서버 정리가 끝나지 않아도(멈춘 폰) 마이크·FGS는 끄기 즉시 멈추고, 다시 켤 수 있다
    @Test
    fun turnOffStopsMicAtOnceAndAllowsTurnOnWhileOldServerIsStillStopping() {
        val c = controller(separateStop = true)
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        c.turnOff()
        assertEquals(1, effects.micStops)
        assertEquals(0, servers[0].stopped)
        assertTrue(c.turnOn(target, true))
        drain()
        assertEquals(Phase.ON, c.snapshot().phase)
        assertEquals(2, servers.size)
        stops.removeFirst()()
        assertEquals(1, servers[0].stopped)
        assertEquals(0, servers[1].stopped)
    }

    @Test
    fun turnOffWhileMicIsStartingStopsTheMicAgain() {
        lateinit var c: SessionController
        val fx = FakeEffects().apply { onStartMic = { c.turnOff() } }
        c = controller(fx = fx)
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        assertEquals(Phase.OFF, c.snapshot().phase)
        assertEquals(1, fx.micStarts)
        assertEquals(2, fx.micStops)
        assertEquals(0, fx.chimes)
        assertEquals(1, servers[0].stopped)
    }

    // M-5 / FR-7: 켜진 지 3시간이 지나면 자동으로 꺼지고 이유를 보여 준다
    @Test
    fun turnsItselfOffAfterThreeHours() {
        val c = controller()
        c.driving = DrivingState.PARKED
        now = 1_000
        c.turnOn(target, true)
        drain()
        now = 1_000 + SessionController.MAX_ON_MS - 1
        assertFalse(c.expireIfDue())
        assertEquals(Phase.ON, c.snapshot().phase)
        now = 1_000 + SessionController.MAX_ON_MS
        assertTrue(c.expireIfDue())
        drain()
        val s = c.snapshot()
        assertEquals(Phase.OFF, s.phase)
        assertEquals(Problem.AUTO_OFF, s.problem)
        assertEquals(1, servers[0].stopped)
        assertEquals(1, effects.micStops)
        assertFalse(c.expireIfDue())
        assertEquals(3 * 60 * 60 * 1000L, SessionController.MAX_ON_MS)
    }

    @Test
    fun autoOffClockRestartsEachSession() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        drain()
        now = SessionController.MAX_ON_MS - 10
        c.turnOff()
        c.turnOn(target, true)
        drain()
        now = SessionController.MAX_ON_MS + 10
        assertFalse(c.expireIfDue())
    }

    // M-4: 소리 입력이 시작되지 않거나 멈추면 차 화면은 "입력 오류", 서버를 거쳐 폰에도 알린다
    @Test
    fun micInputFailureIsShownAndForwarded() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.turnOn(target, true)
        c.setMicInput(true)
        drain()
        assertEquals(true, servers[0].input)
        assertEquals(Mic.ON, c.snapshot().mic)
        c.setMicInput(false)
        assertEquals(false, servers[0].input)
        assertEquals(Mic.FAILED, c.snapshot().mic)
        c.turnOff()
        c.setMicInput(true)
        assertEquals(false, servers[0].input)
        assertEquals(Mic.OFF, c.snapshot().mic)
    }

    // 꺼져 있을 때 시작된 내비 안내도 기억했다가 다음 켜기 때 새 서버에 넘긴다
    @Test
    fun navIsRememberedWhileOffAndPassedToNextServer() {
        val c = controller()
        c.driving = DrivingState.PARKED
        c.setNav(true)
        c.turnOn(target, true)
        drain()
        assertTrue(servers[0].navOn)
        assertTrue(c.snapshot().nav)
        c.setNav(false)
        assertFalse(servers[0].navOn)
        assertFalse(c.snapshot().nav)
        c.turnOff()
        drain()
        assertFalse(c.snapshot().nav)
    }
}
