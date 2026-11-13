package io.cabincaption.car

import io.cabincaption.protocol.SayState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerQueueTest {

    /** 재생마다 [gate]가 열릴 때까지 막는 가짜 스피커. 동시에 둘 이상 재생하면 [overlaps]가 는다. */
    private class FakeSink(private val ok: Boolean = true) : PcmSink {
        val played = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        var gate = CountDownLatch(0)
        @Volatile var active = 0
        @Volatile var overlaps = 0
        @Volatile var stops = 0

        override fun play(pcm: ByteArray, rate: Int, id: String, yieldNow: () -> Boolean, onAudible: () -> Unit): PlayResult {
            synchronized(this) {
                active++
                if (active > 1) overlaps++
            }
            played += id to pcm.size
            onAudible()
            gate.await(3, TimeUnit.SECONDS)
            synchronized(this) { active-- }
            return if (ok && stops == 0) PlayResult.DONE else PlayResult.FAILED
        }

        override fun stop() {
            stops++
            gate.countDown()
        }
    }

    private class Reports {
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun of(id: String): (SayState, String?) -> Unit = { s, r -> events += "$id:${s.wire}${r?.let { ":$it" } ?: ""}" }
        fun audible(id: String): () -> Unit = { events += "$id:audible" }

        /** queued는 받은 스레드가 잠금 밖에서 알려 워커 보고보다 늦을 수 있다(폰은 상태를 되돌리지 않음). 그래서 빼고 본다. */
        fun flow(): List<String> = events.toList().filter { !it.endsWith(":queued") }
        fun waitFor(n: Int) {
            val deadline = System.currentTimeMillis() + 3_000
            while (events.size < n) {
                check(System.currentTimeMillis() < deadline) { "timeout, got $events" }
                Thread.sleep(5)
            }
        }
    }

    private fun u(id: String, bytes: Int = 4) = Utterance(id, 24_000, ByteArray(bytes))

    @Test
    fun playsInOrderWithoutOverlapAndWithChime() {
        val sink = FakeSink()
        val busy = Collections.synchronizedList(mutableListOf<Boolean>())
        val q = SpeakerQueue(sink)
        q.onBusy = { busy += it }
        val r = Reports()
        sink.gate = CountDownLatch(1)
        listOf("a", "b", "c").forEach { q.enqueue(u(it), r.of(it)) }
        r.waitFor(4)
        assertTrue(q.busy)
        sink.gate.countDown()
        r.waitFor(9)
        val deadline = System.currentTimeMillis() + 2_000
        while (q.busy && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(listOf("a", "b", "c"), sink.played.map { it.first })
        assertEquals(Pcm.chime(24_000).size + 4, sink.played[0].second)
        assertEquals(0, sink.overlaps)
        assertEquals(listOf("a:playing", "a:done", "b:playing", "b:done", "c:playing", "c:done"), r.events.filter { !it.endsWith("queued") })
        assertEquals(3, r.events.count { it.endsWith("queued") })
        assertFalse(q.busy)
        assertEquals(listOf(true, false), busy.toList())
    }

    @Test
    fun fullQueueFailsWithBusy() {
        val sink = FakeSink().apply { gate = CountDownLatch(1) }
        val q = SpeakerQueue(sink, maxWaiting = 1)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(2)
        assertTrue(q.enqueue(u("b"), r.of("b")))
        assertFalse(q.enqueue(u("c"), r.of("c")))
        assertTrue("c:failed:busy" in r.events)
        q.shutdown()
    }

    @Test
    fun sinkFailureReportsPlaybackFailure() {
        val q = SpeakerQueue(FakeSink(ok = false))
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(3)
        assertEquals(listOf("a:playing", "a:failed:playback"), r.flow())
    }

    @Test
    fun shutdownDropsWaitingStopsCurrentAndRefusesNew() {
        val sink = FakeSink().apply { gate = CountDownLatch(1) }
        val busy = Collections.synchronizedList(mutableListOf<Boolean>())
        val q = SpeakerQueue(sink)
        q.onBusy = { busy += it }
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        q.enqueue(u("b"), r.of("b"))
        r.waitFor(3)
        q.shutdown()
        r.waitFor(5)
        assertTrue("b:failed:stopped" in r.events)
        assertTrue("a:failed:stopped" in r.events)
        assertEquals(1, sink.stops)
        assertFalse(q.busy)
        assertFalse(q.enqueue(u("c"), r.of("c")))
        assertTrue("c:failed:stopped" in r.events)
        assertEquals(listOf(true, false), busy.toList())
        assertEquals(listOf("a"), sink.played.map { it.first })
    }

    // L-1: 보고 콜백이 던져 워커가 죽어도 busy가 풀리고, 다음 소리는 새 워커가 재생한다
    @Test
    fun workerDeathDoesNotLeaveSpeakerBusy() {
        val sink = FakeSink()
        val q = SpeakerQueue(sink)
        val r = Reports()
        val reached = CountDownLatch(1)
        q.enqueue(u("bad"), { s, _ ->
            if (s == SayState.PLAYING) {
                reached.countDown()
                throw IllegalStateException("report failed")
            }
        })
        assertTrue(reached.await(3, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 3_000
        while (q.busy) {
            check(System.currentTimeMillis() < deadline) { "still busy" }
            Thread.sleep(5)
        }
        q.enqueue(u("next"), r.of("next"))
        r.waitFor(3)
        assertEquals(listOf("next:playing", "next:done"), r.flow())
        assertFalse(q.busy)
    }

    private class Priority : AudioPriority {
        @Volatile var call = false
        @Volatile var nav = false
        override fun call() = call
        override fun nav() = nav
    }

    /** 재생마다 [script]의 다음 동작을 실행한다(없으면 소리를 내고 DONE). */
    private class ScriptSink(vararg script: (yieldNow: () -> Boolean, onAudible: () -> Unit) -> PlayResult) : PcmSink {
        private val steps = ArrayDeque(script.toList())
        val plays = java.util.concurrent.atomic.AtomicInteger()
        override fun play(pcm: ByteArray, rate: Int, id: String, yieldNow: () -> Boolean, onAudible: () -> Unit): PlayResult {
            plays.incrementAndGet()
            val step = synchronized(steps) { steps.removeFirstOrNull() }
            if (step != null) return step(yieldNow, onAudible)
            onAudible()
            return PlayResult.DONE
        }
        override fun stop() = Unit
    }

    private fun queue(sink: PcmSink, p: AudioPriority, maxNavWaitMs: Long = 3_000) =
        SpeakerQueue(sink, p, maxNavWaitMs = maxNavWaitMs, yieldGraceMs = 100, pollMs = 5)

    @Test
    fun navGuidanceMakesItWaitThenPlay() {
        val p = Priority().apply { nav = true }
        val sink = ScriptSink()
        val busy = Collections.synchronizedList(mutableListOf<Boolean>())
        val q = queue(sink, p)
        q.onBusy = { busy += it }
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(2)
        Thread.sleep(50)
        assertEquals(listOf("a:waiting:nav"), r.flow())
        assertEquals(0, sink.plays.get())
        assertFalse(q.busy)
        p.nav = false
        r.waitFor(4)
        assertEquals(listOf("a:waiting:nav", "a:playing", "a:done"), r.flow())
        assertEquals(listOf(true, false), busy.toList())
    }

    @Test
    fun callRefusesWithoutPlaying() {
        val p = Priority().apply { call = true }
        val sink = ScriptSink()
        val q = queue(sink, p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(2)
        assertEquals(listOf("a:failed:call"), r.flow())
        assertEquals(0, sink.plays.get())
    }

    @Test
    fun callDuringNavWaitRefuses() {
        val p = Priority().apply { nav = true }
        val q = queue(ScriptSink(), p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(2)
        p.call = true
        r.waitFor(3)
        assertEquals("a:failed:call", r.flow().last())
    }

    // 재생 중 내비 안내가 끼면 멈추고 기다렸다가 처음부터 다시 재생한다
    @Test
    fun navInterruptionReplaysFromStart() {
        val p = Priority()
        val sink = ScriptSink({ _, _ -> p.nav = true; PlayResult.YIELDED })
        val q = queue(sink, p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(3)
        assertEquals("a:waiting:nav", r.flow().last())
        p.nav = false
        r.waitFor(5)
        assertEquals(listOf("a:playing", "a:waiting:nav", "a:playing", "a:done"), r.flow())
        assertEquals(2, sink.plays.get())
    }

    @Test
    fun navLongerThanLimitFails() {
        val p = Priority().apply { nav = true }
        val q = queue(ScriptSink(), p, maxNavWaitMs = 100)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(3)
        assertEquals(listOf("a:waiting:nav", "a:failed:nav"), r.flow())
    }

    @Test
    fun interruptedTooOftenFails() {
        val p = Priority()
        val interrupt: (() -> Boolean, () -> Unit) -> PlayResult = { _, _ ->
            p.nav = true
            thread { Thread.sleep(50); p.nav = false }
            PlayResult.YIELDED
        }
        val sink = ScriptSink(*Array(SpeakerQueue.MAX_PLAYS) { interrupt })
        val q = queue(sink, p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        val deadline = System.currentTimeMillis() + 3_000
        while (r.events.toList().none { it.startsWith("a:failed") || it == "a:done" }) {
            check(System.currentTimeMillis() < deadline) { "timeout, got ${r.events}" }
            Thread.sleep(5)
        }
        assertEquals("a:failed:nav", r.flow().last())
        assertEquals(SpeakerQueue.MAX_PLAYS, sink.plays.get())
    }

    // 포커스를 잃었는데 내비·통화 재생이 보이지 않으면(다른 앱 소리) 다시 재생하지 않고 실패
    @Test
    fun focusLostToOtherSoundFails() {
        val sink = ScriptSink({ _, _ -> PlayResult.YIELDED })
        val q = queue(sink, Priority())
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(3)
        assertEquals(listOf("a:playing", "a:failed:focus"), r.flow())
        assertEquals(1, sink.plays.get())
    }

    @Test
    fun sinkSeesNavAndCallAsYieldCondition() {
        val p = Priority()
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        val sink = ScriptSink({ y, _ ->
            seen += y()
            p.call = true
            seen += y()
            PlayResult.YIELDED
        })
        val q = queue(sink, p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(3)
        assertEquals(listOf(false, true), seen.toList())
        assertEquals("a:failed:call", r.flow().last())
    }

    @Test
    fun shutdownWhileWaitingForNavFailsStopped() {
        val p = Priority().apply { nav = true }
        val q = queue(ScriptSink(), p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"))
        r.waitFor(2)
        q.shutdown()
        r.waitFor(3)
        assertEquals("a:failed:stopped", r.flow().last())
    }

    // PLAYING은 재생 시도 직전 그대로이고, 소리가 난 시점은 그 뒤 따로 알린다
    @Test
    fun audibleComesAfterPlayingAndOnlyWhenSinkMadeSound() {
        val q = queue(ScriptSink(), Priority())
        val r = Reports()
        q.enqueue(u("a"), r.of("a"), r.audible("a"))
        r.waitFor(4)
        assertEquals(listOf("a:playing", "a:audible", "a:done"), r.flow())
    }

    // 포커스 거절: 보낸 폰은 playing을 받지만 소리가 나지 않았으므로 audible은 없다
    @Test
    fun focusRefusedIsNotAudible() {
        val q = queue(ScriptSink({ _, _ -> PlayResult.YIELDED }), Priority())
        val r = Reports()
        q.enqueue(u("a"), r.of("a"), r.audible("a"))
        r.waitFor(3)
        assertEquals(listOf("a:playing", "a:failed:focus"), r.flow())
    }

    @Test
    fun yieldBeforeAnySoundThenCallIsNotAudible() {
        val p = Priority()
        val q = queue(ScriptSink({ _, _ -> p.call = true; PlayResult.YIELDED }), p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"), r.audible("a"))
        r.waitFor(3)
        assertEquals(listOf("a:playing", "a:failed:call"), r.flow())
    }

    @Test
    fun callRefusalAndFullQueueAreNotAudible() {
        val p = Priority().apply { call = true }
        val r = Reports()
        queue(ScriptSink(), p).enqueue(u("a"), r.of("a"), r.audible("a"))
        r.waitFor(2)
        assertEquals(listOf("a:failed:call"), r.flow())

        val sink = FakeSink().apply { gate = CountDownLatch(1) }
        val q = SpeakerQueue(sink, maxWaiting = 1)
        q.enqueue(u("b"), r.of("b"))
        val deadline = System.currentTimeMillis() + 3_000
        while (sink.played.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "timeout" }
            Thread.sleep(5)
        }
        assertTrue(q.enqueue(u("c"), r.of("c")))
        assertFalse(q.enqueue(u("d"), r.of("d"), r.audible("d")))
        assertTrue("d:failed:busy" in r.events)
        assertFalse("d:audible" in r.events)
        q.shutdown()
    }

    // 소리가 난 뒤 실패해도 audible은 이미 나갔다(글은 공유된 채로 둔다, ADR-0004 결정 8)
    @Test
    fun audibleThenSinkFailureKeepsAudible() {
        val q = queue(ScriptSink({ _, a -> a(); PlayResult.FAILED }), Priority())
        val r = Reports()
        q.enqueue(u("a"), r.of("a"), r.audible("a"))
        r.waitFor(4)
        assertEquals(listOf("a:playing", "a:audible", "a:failed:playback"), r.flow())
    }

    // 큐는 재생마다 알린다. 메시지당 한 번으로 묶는 것은 CarServer의 AtomicBoolean이다
    @Test
    fun replayReportsAudibleForEachPlayThatMadeSound() {
        val p = Priority()
        val q = queue(ScriptSink({ _, a -> a(); p.nav = true; PlayResult.YIELDED }), p)
        val r = Reports()
        q.enqueue(u("a"), r.of("a"), r.audible("a"))
        r.waitFor(4)
        assertEquals("a:waiting:nav", r.flow().last())
        p.nav = false
        r.waitFor(7)
        assertEquals(listOf("a:playing", "a:audible", "a:waiting:nav", "a:playing", "a:audible", "a:done"), r.flow())
    }
}
