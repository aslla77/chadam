package io.cabincaption.car

import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

enum class PlayResult {
    DONE,
    FAILED,

    /** 오디오 포커스를 못 받았거나 잃었거나, [PcmSink.play]의 양보 조건이 참이 되어 멈춤. */
    YIELDED,
}

/**
 * 실제 소리 출력. [play]는 끝나거나 멈출 때까지 막는다. [yieldNow]가 참이 되면 바로 멈춘다. [stop]은 다른 스레드에서 재생을 끊는다.
 * [play]의 onAudible은 포커스를 받고 소리가 실제로 나가기 시작했을 때 재생 한 번에 한 번만 재생 스레드에서 부른다. 소리 없이 끝나면 부르지 않는다.
 */
interface PcmSink {
    fun play(pcm: ByteArray, rate: Int, id: String, yieldNow: () -> Boolean, onAudible: () -> Unit): PlayResult

    fun stop()
}

/**
 * 차 스피커 큐: 한 번에 한 소리, 들어온 순서대로. 재생마다 알림음 + 게인 상한(Pcm.withChime).
 * [busy]가 참인 동안(알림음 포함) 차 마이크 소리를 폰에 올리지 않는다(반이중, ADR-0002).
 * 차 안 다른 소리 우선(ADR-0002): 통화 중이면 재생하지 않고 실패(call). 내비 안내 중이면 WAITING을 알리고 끝나면 재생한다.
 * 한 문장이 내비를 기다린 시간이 모두 [maxNavWaitMs]를 넘거나 [MAX_PLAYS]번 끊기면 실패(nav). 재생 중 끊기면 처음부터 다시 재생한다.
 */
class SpeakerQueue(
    private val sink: PcmSink,
    private val priority: AudioPriority = AudioPriority.NONE,
    private val maxWaiting: Int = MAX_WAITING,
    private val log: (String) -> Unit = {},
    private val maxNavWaitMs: Long = MAX_NAV_WAIT_MS,
    private val yieldGraceMs: Long = YIELD_GRACE_MS,
    private val pollMs: Long = POLL_MS,
) {
    private class Job(val u: Utterance, val report: (SayState, String?) -> Unit, val onAudible: () -> Unit)

    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()
    private val waiting = ArrayDeque<Job>()
    private var worker: Thread? = null
    @Volatile
    private var shut = false
    private val busyFlag = AtomicBoolean(false)

    val busy: Boolean get() = busyFlag.get()

    /** 재생 시작·끝(큐가 빌 때·내비를 기다리기 시작할 때)에만 불린다. */
    @Volatile
    var onBusy: (Boolean) -> Unit = {}

    /**
     * 받으면 QUEUED를, 못 받으면 FAILED를 [report]로 알린다.
     * PLAYING은 재생 시도 직전 알림이고 소리가 났다는 뜻이 아니다. 소리가 난 시점은 [onAudible]이다(다시 읽으면 또 불린다).
     */
    fun enqueue(u: Utterance, report: (SayState, String?) -> Unit, onAudible: () -> Unit = {}): Boolean {
        val accepted = lock.withLock {
            if (shut || waiting.size >= maxWaiting) {
                false
            } else {
                waiting.addLast(Job(u, report, onAudible))
                if (worker == null) worker = thread(name = "car-speaker", isDaemon = true) { run() }
                hasWork.signalAll()
                true
            }
        }
        if (accepted) {
            log("say id=${u.id} queued rate=${u.rate} bytes=${u.pcm.size}")
            report(SayState.QUEUED, null)
        } else {
            log("say id=${u.id} rejected: ${if (shut) "stopped" else "queue full"}")
            report(SayState.FAILED, if (shut) REASON_STOPPED else REASON_BUSY)
        }
        return accepted
    }

    // 보고 콜백 등에서 예외·Error로 워커가 죽어도 busy가 남지 않고, 다음 소리 때 새 워커가 뜬다(L-1)
    private fun run() {
        try {
            loop()
        } finally {
            lock.withLock { if (worker === Thread.currentThread()) worker = null }
            setBusy(false)
        }
    }

    private fun loop() {
        while (true) {
            val job = lock.withLock {
                while (waiting.isEmpty() && !shut) hasWork.await()
                if (shut) return
                waiting.removeFirst()
            }
            val (state, reason) = playJob(job)
            job.report(state, reason)
            if (lock.withLock { waiting.isEmpty() }) setBusy(false)
        }
    }

    private fun playJob(job: Job): Pair<SayState, String?> {
        val id = job.u.id
        val pcm = Pcm.withChime(job.u.pcm, job.u.rate)
        var waitLeft = maxNavWaitMs
        var plays = 0
        while (true) {
            if (isShut()) return SayState.FAILED to REASON_STOPPED
            if (priority.call()) {
                log("say id=$id not played: call")
                return SayState.FAILED to Wire.SAID_CALL
            }
            if (priority.nav()) {
                setBusy(false)
                job.report(SayState.WAITING, Wire.SAID_NAV)
                log("say id=$id waiting for navigation (left=${waitLeft}ms)")
                val t0 = monoMs()
                val ended = waitFor(waitLeft) { !priority.nav() || priority.call() }
                waitLeft -= monoMs() - t0
                if (ended) continue
                if (isShut()) return SayState.FAILED to REASON_STOPPED
                log("say id=$id not played: navigation longer than ${maxNavWaitMs}ms")
                return SayState.FAILED to Wire.SAID_NAV
            }
            if (plays == MAX_PLAYS) {
                log("say id=$id not played: interrupted $plays times")
                return SayState.FAILED to Wire.SAID_NAV
            }
            plays++
            setBusy(true)
            job.report(SayState.PLAYING, null)
            val r = try {
                sink.play(pcm, job.u.rate, id, { priority.nav() || priority.call() }, job.onAudible)
            } catch (e: RuntimeException) {
                log("say id=$id sink error ${e.javaClass.simpleName}")
                PlayResult.FAILED
            }
            when (r) {
                PlayResult.DONE -> return SayState.DONE to null
                PlayResult.FAILED -> return SayState.FAILED to if (isShut()) REASON_STOPPED else REASON_PLAYBACK
                PlayResult.YIELDED -> {
                    // 포커스를 먼저 잃고 내비·통화 재생은 조금 늦게 보일 수 있다
                    if (!isShut() && !waitFor(yieldGraceMs) { priority.nav() || priority.call() }) {
                        log("say id=$id stopped: audio focus lost to another sound")
                        return SayState.FAILED to REASON_FOCUS
                    }
                }
            }
        }
    }

    /** [done]이 참이 될 때까지 최대 [ms] 기다린다. 끄면 바로 false. */
    private fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val until = monoMs() + ms
        while (true) {
            if (done()) return true
            val left = until - monoMs()
            if (left <= 0) return false
            lock.withLock {
                if (shut) return false
                hasWork.await(minOf(left, pollMs), TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun isShut() = lock.withLock { shut }

    private fun setBusy(on: Boolean) {
        if (busyFlag.compareAndSet(!on, on)) onBusy(on)
    }

    /** 대기 중인 소리는 실패로 알리고, 재생 중인 소리는 끊는다. 다시 쓰지 않는다. */
    fun shutdown() {
        val dropped = lock.withLock {
            shut = true
            val d = waiting.toList()
            waiting.clear()
            hasWork.signalAll()
            d
        }
        dropped.forEach { it.report(SayState.FAILED, REASON_STOPPED) }
        sink.stop()
        setBusy(false)
    }

    companion object {
        const val MAX_WAITING = 4
        const val REASON_BUSY = "busy"
        const val REASON_STOPPED = "stopped"
        const val REASON_PLAYBACK = "playback"
        const val REASON_FOCUS = "focus"

        /** 오너 지시(2026-11-03): 내비 안내를 최대 15초 기다린다. */
        const val MAX_NAV_WAIT_MS = 15_000L
        const val MAX_PLAYS = 3
        const val YIELD_GRACE_MS = 500L
        const val POLL_MS = 50L

        private fun monoMs(): Long = System.nanoTime() / 1_000_000
    }
}
