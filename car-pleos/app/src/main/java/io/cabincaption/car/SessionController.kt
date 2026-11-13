package io.cabincaption.car

import java.io.IOException

/**
 * 켜기/끄기 상태 머신. 켜기는 정차 중에만, 끄기는 언제나. 세션마다 서버(토큰·키 포함)를 새로 만들고 끄면 버린다.
 * 끄기는 마이크·FGS를 바로 멈추고, 서버 정리는 [runStop]으로 따로 돌린다(정리가 늦어도 다시 켜기를 막지 않음).
 * 켜진 지 [maxOnMs]가 지나면 [expireIfDue]가 끈다(FR-7).
 */
class SessionController(
    private val newServer: () -> SessionServer,
    private val effects: Effects,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runAsync: (() -> Unit) -> Unit,
    private val runStop: (() -> Unit) -> Unit = runAsync,
    private val listenPort: Int = LISTEN_PORT,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxOnMs: Long = MAX_ON_MS,
) {
    interface Effects {
        fun chime()

        /** 마이크 FGS를 시작했으면 true. */
        fun startMic(): Boolean

        fun stopMic()
    }

    enum class Phase { OFF, STARTING, ON }

    /** [FAILED] = 마이크 서비스는 켰지만 소리 입력을 시작하지 못했거나 도중에 멈춤(M-4). */
    enum class Mic { OFF, ON, NO_PERMISSION, FAILED }

    enum class Problem { DRIVING, DRIVING_UNKNOWN, NO_HOST, PORT_FAILED, AUTO_OFF }

    data class Snapshot(
        val phase: Phase,
        val driving: DrivingState,
        val phones: Int,
        val qrText: String?,
        val mic: Mic,
        val problem: Problem?,
        val speakerBusy: Boolean,
        val paused: Boolean,
        val micSilenced: Boolean,
        val nav: Boolean,
        val target: JoinTarget?,
    )

    @Volatile
    var driving: DrivingState = DrivingState.UNKNOWN

    private var phase = Phase.OFF
    private var mic = Mic.OFF
    private var micInput: Boolean? = null
    private var problem: Problem? = null
    private var turnedOnAt = 0L
    @Volatile
    private var server: SessionServer? = null
    private var target: JoinTarget? = null
    private var nav = false

    /** 켜기 요청. 바로 거절하면 false(이유는 [Snapshot.problem]). */
    @Synchronized
    fun turnOn(target: JoinTarget?, micPermitted: Boolean): Boolean {
        if (phase != Phase.OFF) return false
        problem = when {
            driving == DrivingState.DRIVING -> Problem.DRIVING
            driving == DrivingState.UNKNOWN -> Problem.DRIVING_UNKNOWN
            target == null -> Problem.NO_HOST
            else -> null
        }
        if (problem != null) return false
        phase = Phase.STARTING
        this.target = target
        micInput = null
        turnedOnAt = elapsed()
        val s = newServer()
        s.setNav(nav)
        server = s
        runAsync { start(s, micPermitted) }
        return true
    }

    private fun start(s: SessionServer, micPermitted: Boolean) {
        try {
            s.start(listenPort)
        } catch (e: IOException) {
            synchronized(this) {
                if (server === s) {
                    server = null
                    target = null
                    phase = Phase.OFF
                    problem = Problem.PORT_FAILED
                }
            }
            return
        }
        synchronized(this) { if (server !== s) return }
        val micOn = micPermitted && effects.startMic()
        val stillOn = synchronized(this) {
            if (server !== s) {
                false
            } else {
                phase = Phase.ON
                mic = if (micOn) Mic.ON else Mic.NO_PERMISSION
                true
            }
        }
        // 마이크를 켜는 사이에 꺼졌으면 방금 켠 마이크를 다시 끈다
        if (!stillOn) {
            if (micOn) effects.stopMic()
            return
        }
        effects.chime()
    }

    @Synchronized
    fun turnOff() = off(problem = null)

    private fun off(problem: Problem?) {
        if (phase == Phase.OFF) return
        val s = server
        server = null
        target = null
        phase = Phase.OFF
        mic = Mic.OFF
        micInput = null
        this.problem = problem
        effects.stopMic()
        if (s != null) runStop { s.stop() }
    }

    /** 켜진 지 [maxOnMs]가 지났으면 끄고 true(FR-7 자동 꺼짐). 주기적으로 부른다. */
    @Synchronized
    fun expireIfDue(): Boolean {
        if (phase == Phase.OFF || elapsed() - turnedOnAt < maxOnMs) return false
        off(Problem.AUTO_OFF)
        return true
    }

    /** 소리 입력이 시작됨(true) / 시작 실패·도중 멈춤(false). 마이크 서비스가 부른다. 폰에도 알린다(M-4). */
    fun setMicInput(on: Boolean) {
        val s = synchronized(this) {
            val cur = server ?: return
            micInput = on
            cur
        }
        s.setMicInput(on)
    }

    /** 캡처 스레드에서 불린다. 켜져 있을 때만 서버로 넘기고, 아니면 버린다. */
    fun carPcm(pcm: ByteArray, captureMs: Long) {
        val s = synchronized(this) { if (phase == Phase.ON) server else null }
        s?.carPcm(pcm, captureMs)
    }

    /** 내비 안내 재생 시작·끝(재생 구성 감시가 부른다). 꺼져 있어도 기억해 두고 다음 켜기 때 서버에 넘긴다. */
    fun setNav(on: Boolean) {
        val s = synchronized(this) {
            nav = on
            server
        }
        s?.setNav(on)
    }

    fun setMicSilenced(on: Boolean) {
        val s = synchronized(this) { if (phase == Phase.ON) server else null }
        s?.setMicSilenced(on)
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val s = server
        val t = target
        val on = phase == Phase.ON && s != null
        return Snapshot(
            phase = phase,
            driving = driving,
            phones = if (on) s!!.phones() else 0,
            qrText = if (on && t != null) t.uri(s!!.token(clock())) else null,
            mic = if (mic == Mic.ON && micInput == false) Mic.FAILED else mic,
            problem = problem,
            speakerBusy = on && s!!.speakerBusy(),
            paused = on && s!!.paused(),
            micSilenced = on && s!!.micSilenced(),
            nav = on && s!!.nav(),
            target = if (on) t else null,
        )
    }

    companion object {
        const val LISTEN_PORT = 47000

        /** 시연 설정을 열고 저장할 수 있는 때: 정차(PARKED)이고 세션이 꺼져 있을 때만. UNKNOWN은 거부(fail-closed). */
        fun canEditSettings(driving: DrivingState, phase: Phase): Boolean =
            driving == DrivingState.PARKED && phase == Phase.OFF

        /** 앱을 연 뒤 세션을 저절로 열어도 되는 때: 정차를 확인했을 때만. UNKNOWN은 기다린다(fail-closed). */
        fun canAutoOpen(driving: DrivingState): Boolean = driving == DrivingState.PARKED

        /** 자동 열기를 기다리는 동안 보여 줄 이유. 앱을 막 열어 주행 상태를 아직 못 받았을 때는 잠시 보여 주지 않는다. */
        fun autoOpenWait(driving: DrivingState, waitedMs: Long): Problem? = when {
            driving == DrivingState.DRIVING -> Problem.DRIVING
            driving == DrivingState.UNKNOWN && waitedMs >= UNKNOWN_GRACE_MS -> Problem.DRIVING_UNKNOWN
            else -> null
        }

        const val UNKNOWN_GRACE_MS = 5_000L

        /** FR-7 "최대 3시간 뒤 자동으로 꺼진다"(3시간은 PRD의 [가정] 값). */
        const val MAX_ON_MS = 3 * 60 * 60 * 1000L
    }
}
