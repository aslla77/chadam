package io.cabincaption.car

import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.Ctrl
import io.cabincaption.protocol.Frame
import io.cabincaption.protocol.FrameCodec
import io.cabincaption.protocol.FrameType
import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarServerTest {

    private val driving = AtomicBoolean(false)
    private val calling = AtomicBoolean(false)
    private val navOn = AtomicBoolean(false)
    private val priority = object : AudioPriority {
        override fun call() = calling.get()
        override fun nav() = navOn.get()
    }
    private var lobby = Lobby(JoinTokens(ttlMs = 60_000), maxPhones = 2)
    private lateinit var server: CarServer
    private var port = 0
    private val clients = mutableListOf<Socket>()

    /** 재생마다 [script]의 앞에서 하나를 꺼내 실행한다. 없으면 소리를 내고 [gate]까지 기다렸다 DONE. */
    private inner class GatedSink : PcmSink {
        val played = CopyOnWriteArrayList<Pair<String, Int>>()
        @Volatile var gate = CountDownLatch(0)
        val script = ConcurrentLinkedQueue<(onAudible: () -> Unit) -> PlayResult>()
        override fun play(pcm: ByteArray, rate: Int, id: String, yieldNow: () -> Boolean, onAudible: () -> Unit): PlayResult {
            played += id to pcm.size
            script.poll()?.let { return it(onAudible) }
            onAudible()
            gate.await(3, TimeUnit.SECONDS)
            return PlayResult.DONE
        }
        override fun stop() = gate.countDown()
    }

    private val sink = GatedSink()

    private val logs: MutableList<String> = CopyOnWriteArrayList<String>()

    /** 기본 테스트는 ping·끊김 감시가 끼어들지 않게 길게 둔다. 살아 있음·느린 폰 테스트는 [restart]로 짧게. */
    private fun newServer(
        pingMs: Long = 60_000,
        peerTimeoutMs: Int = 60_000,
        writeTimeoutMs: Long = 60_000,
        audioBacklog: Int = SendQueue.MAX_AUDIO,
    ) = CarServer(
        lobby, isDriving = { driving.get() }, speaker = SpeakerQueue(sink, priority, log = { logs += it }), log = { logs += it },
        helloTimeoutMs = 1_000, maxSockets = 4, pingMs = pingMs, peerTimeoutMs = peerTimeoutMs, writeTimeoutMs = writeTimeoutMs,
        audioBacklog = audioBacklog,
    )

    @Before
    fun setUp() {
        server = newServer()
        port = server.start(0)
    }

    private fun restart(
        pingMs: Long = 60_000, peerTimeoutMs: Int = 60_000, writeTimeoutMs: Long = 60_000, audioBacklog: Int = SendQueue.MAX_AUDIO, phones: Int = 2,
    ) {
        server.stop()
        lobby = Lobby(JoinTokens(ttlMs = 60_000), maxPhones = phones)
        server = newServer(pingMs, peerTimeoutMs, writeTimeoutMs, audioBacklog)
        port = server.start(0)
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.stop()
    }

    /** [receiveBuffer]를 작게 주고 읽지 않으면 "받지 않는 폰"이 된다. */
    private inner class Client(receiveBuffer: Int? = null) {
        val socket = Socket().also {
            receiveBuffer?.let { n -> it.receiveBufferSize = n }
            it.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            it.soTimeout = 3_000
            clients += it
        }
        val input: InputStream = BufferedInputStream(socket.getInputStream())
        var key: String? = null

        fun send(f: Frame) = FrameCodec.write(socket.getOutputStream(), f)

        fun sendRaw(b: ByteArray) {
            socket.getOutputStream().write(b)
            socket.getOutputStream().flush()
        }

        fun next(): Frame = FrameCodec.read(input)!!

        fun ctrl(): Map<String, String> {
            val f = next()
            assertEquals(FrameType.CTRL, f.type)
            return Ctrl.decode(f.body)
        }

        fun chunk(): CarChunk {
            val f = next()
            assertEquals(FrameType.CAR_PCM, f.type)
            return CarChunk.parse(f.body)!!
        }

        fun assertClosed() {
            val end = try {
                FrameCodec.read(input)
            } catch (e: IOException) {
                null
            }
            assertNull(end)
        }
    }

    /** 환영 뒤 차가 바로 알려 주는 상태(mic·pause·nav)까지 읽는다. */
    private fun joined(): Client = hello("token" to server.token(System.currentTimeMillis())).also {
        val w = it.ctrl()
        assertEquals("welcome", w["t"])
        it.key = w["key"]
        assertEquals(mapOf("t" to "mic", "silenced" to "0", "input" to "0"), it.ctrl())
        assertEquals(mapOf("t" to "pause", "on" to "0"), it.ctrl())
        assertEquals(mapOf("t" to "nav", "on" to "0"), it.ctrl())
        waitFor { server.phones() >= 1 }
    }

    /** 다른 스레드에서 끝까지 읽어 오디오 순번과 제어 메시지를 모은다(빨리 받는 폰). */
    private inner class Drain(c: Client) {
        val seqs: MutableList<Long> = CopyOnWriteArrayList<Long>()
        val ctrls: MutableList<Map<String, String>> = CopyOnWriteArrayList<Map<String, String>>()
        val thread = Thread {
            try {
                while (true) {
                    val f = FrameCodec.read(c.input) ?: break
                    if (f.type == FrameType.CAR_PCM) seqs += CarChunk.parse(f.body)!!.seq else ctrls += Ctrl.decode(f.body)
                }
            } catch (_: IOException) {
            }
        }

        init {
            c.socket.soTimeout = 0
            thread.isDaemon = true
            thread.start()
        }
    }

    /** 환영만 받고 그 뒤로는 읽지 않는 폰. */
    private fun stalled(): Client = Client(receiveBuffer = 2_048).also {
        it.send(Wire.hello(mapOf("token" to server.token(System.currentTimeMillis()))))
        assertEquals("welcome", it.ctrl()["t"])
    }

    private fun pcm(n: Int) = ByteArray(CarChunk.CHUNK_BYTES) { n.toByte() }

    private fun Client.say(id: String, pcm: ByteArray, rate: Int = 24_000, text: String? = null) {
        send(Wire.say(id, rate, text))
        pcm.toList().chunked(Wire.PCM_CHUNK).forEach { send(Wire.pcm(it.toByteArray())) }
        send(Wire.sayEnd(id))
    }

    private fun hello(vararg fields: Pair<String, String>): Client = Client().also { it.send(Wire.hello(mapOf(*fields))) }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timeout" }
            Thread.sleep(10)
        }
    }

    @Test
    fun validTokenGetsWelcomeWithKey() {
        val c = hello("token" to server.token(System.currentTimeMillis()))
        val m = c.ctrl()
        assertEquals("welcome", m["t"])
        assertEquals(22, m["key"]!!.length)
        waitFor { server.phones() == 1 }
    }

    @Test
    fun wrongTokenIsRejectedAndClosed() {
        server.token(System.currentTimeMillis())
        val c = hello("token" to "AAAAAAAAAAAAAAAAAAAAAA")
        assertEquals(mapOf("t" to "reject", "reason" to "invalid"), c.ctrl())
        c.assertClosed()
        assertEquals(0, server.phones())
    }

    @Test
    fun reusedTokenIsRejected() {
        val t = server.token(System.currentTimeMillis())
        assertEquals("welcome", hello("token" to t).ctrl()["t"])
        val second = hello("token" to t)
        assertEquals("invalid", second.ctrl()["reason"])
        second.assertClosed()
    }

    @Test
    fun drivingRejectsNewJoinButAllowsReconnectWithRotatedKey() {
        val key = hello("token" to server.token(System.currentTimeMillis())).ctrl()["key"]!!
        driving.set(true)
        assertEquals("driving", hello("token" to server.token(System.currentTimeMillis())).ctrl()["reason"])
        val back = hello("key" to key).ctrl()
        assertEquals("welcome", back["t"])
        assertEquals(22, back["key"]!!.length)
        assertTrue(back["key"] != key)
        assertEquals("invalid", hello("key" to key).ctrl()["reason"])
    }

    @Test
    fun capacityRejectsExtraPhone() {
        repeat(2) { assertEquals("welcome", hello("token" to server.token(System.currentTimeMillis())).ctrl()["t"]) }
        assertEquals("full", hello("token" to server.token(System.currentTimeMillis())).ctrl()["reason"])
    }

    @Test
    fun firstFrameMustBeHello() {
        val c = Client()
        c.send(Frame(FrameType.CTRL, Ctrl.encode(mapOf("token" to server.token(System.currentTimeMillis())))))
        c.assertClosed()
        assertEquals(0, server.phones())
    }

    @Test
    fun oversizedHelloIsClosedWithoutReply() {
        val c = Client()
        c.send(Frame(FrameType.HELLO, ByteArray(Wire.HELLO_MAX_BODY + 1)))
        c.assertClosed()
    }

    @Test
    fun malformedHelloBodyIsRejected() {
        val c = Client()
        c.send(Frame(FrameType.HELLO, "not json".toByteArray()))
        assertEquals("invalid", c.ctrl()["reason"])
        c.assertClosed()
    }

    @Test
    fun truncatedHelloTimesOut() {
        val c = Client()
        c.sendRaw(byteArrayOf(1, 0, 0, 0, 10, 1, 2))
        c.assertClosed()
    }

    @Test
    fun silentClientTimesOut() {
        Client().assertClosed()
    }

    @Test
    fun disconnectLowersPhoneCount() {
        val c = hello("token" to server.token(System.currentTimeMillis()))
        c.ctrl()
        waitFor { server.phones() == 1 }
        c.socket.close()
        waitFor { server.phones() == 0 }
    }

    @Test
    fun sayIsQueuedPlayedAndReportedToSenderOnly() {
        val a = joined()
        val b = joined()
        sink.gate = CountDownLatch(1)
        a.say("s1", ByteArray(40_000))
        // QUEUED(받은 스레드)와 PLAYING·speaker(재생 스레드)는 순서가 정해지지 않는다. 폰은 상태를 되돌리지 않는다
        val seenA = listOf(a.ctrl(), a.ctrl(), a.ctrl())
        assertTrue(mapOf("t" to "said", "id" to "s1", "state" to "queued") in seenA)
        assertTrue(mapOf("t" to "said", "id" to "s1", "state" to "playing") in seenA)
        assertTrue(mapOf("t" to "speaker", "on" to "1") in seenA)
        assertEquals(mapOf("t" to "speaker", "on" to "1"), b.ctrl())
        assertTrue(server.speakerBusy())
        sink.gate.countDown()
        val doneA = listOf(a.ctrl(), a.ctrl())
        assertTrue(mapOf("t" to "said", "id" to "s1", "state" to "done") in doneA)
        assertTrue(mapOf("t" to "speaker", "on" to "0") in doneA)
        assertEquals(mapOf("t" to "speaker", "on" to "0"), b.ctrl())
        assertEquals(listOf("s1"), sink.played.map { it.first })
        assertEquals(Pcm.chime(24_000).size + 40_000, sink.played[0].second)
        waitFor { !server.speakerBusy() }
    }

    @Test
    fun badSayIsReportedFailedAndConnectionStays() {
        val a = joined()
        a.say("s1", ByteArray(0))
        assertEquals(mapOf("t" to "said", "id" to "s1", "state" to "failed", "reason" to "empty"), a.ctrl())
        a.send(Wire.say("s2", 1_000))
        assertEquals(mapOf("t" to "said", "id" to "s2", "state" to "failed", "reason" to "format"), a.ctrl())
        a.say("s3", ByteArray(4))
        val seen = (1..5).map { a.ctrl() }
        assertTrue(seen.toString(), mapOf("t" to "said", "id" to "s3", "state" to SayState.DONE.wire) in seen)
        assertEquals(1, server.phones())
    }

    @Test
    fun lateJoinerLearnsSpeakerIsBusy() {
        val a = joined()
        sink.gate = CountDownLatch(1)
        a.say("s1", ByteArray(4))
        waitFor { server.speakerBusy() }
        val b = joined()
        assertEquals(mapOf("t" to "speaker", "on" to "1"), b.ctrl())
        sink.gate.countDown()
    }

    @Test
    fun stopSendsOffClosesAllAndForgetsKeys() {
        val a = joined()
        val key = a.key!!
        val b = joined()
        waitFor { server.phones() == 2 }
        server.stop()
        assertEquals(mapOf("t" to "off"), a.ctrl())
        assertEquals(mapOf("t" to "off"), b.ctrl())
        a.assertClosed()
        b.assertClosed()
        assertEquals(0, server.phones())
        assertTrue(lobby.admit(mapOf("key" to key), 99, false, 0) is Admit.Rejected)
        assertThrows(IOException::class.java) {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }
    }

    @Test
    fun noPhoneMeansNoUpload() {
        repeat(3) { server.carPcm(pcm(it), 1_000L + it) }
        assertEquals(UploadGate.Counters(3, 0, 3, 0, 0, 0, 0), server.uploadCounters())
    }

    @Test
    fun connectedPhonesGetEveryChunkInOrder() {
        val a = joined()
        val b = joined()
        waitFor { server.phones() == 2 }
        server.carPcm(pcm(1), 5_000)
        server.carPcm(pcm(2), 5_100)
        for (c in listOf(a, b)) {
            val x = c.chunk()
            val y = c.chunk()
            assertEquals(listOf(0L, 1L), listOf(x.seq, y.seq))
            assertEquals(listOf(5_000L, 5_100L), listOf(x.captureMs, y.captureMs))
            assertEquals(1.toByte(), x.pcm[0])
            assertEquals(CarChunk.CHUNK_BYTES, y.pcm.size)
        }
        assertEquals(2L, server.uploadCounters().sent)
    }

    @Test
    fun anyPhoneCanPauseForEveryoneAndNothingIsUploadedWhilePaused() {
        val a = joined()
        val b = joined()
        waitFor { server.phones() == 2 }
        b.send(Wire.pause(true))
        assertEquals(mapOf("t" to "pause", "on" to "1"), a.ctrl())
        assertEquals(mapOf("t" to "pause", "on" to "1"), b.ctrl())
        assertTrue(server.paused())
        repeat(3) { server.carPcm(pcm(it), it.toLong()) }
        a.send(Wire.pause(false))
        assertEquals(mapOf("t" to "pause", "on" to "0"), a.ctrl())
        assertEquals(mapOf("t" to "pause", "on" to "0"), b.ctrl())
        server.carPcm(pcm(9), 9)
        assertEquals(3L, a.chunk().seq)
        assertEquals(3L, b.chunk().seq)
        assertEquals(UploadGate.Counters(4, 1, 0, 3, 0, 0, 0), server.uploadCounters())
    }

    @Test
    fun repeatedOrBadPauseValuesAreIgnored() {
        val a = joined()
        a.send(Wire.ctrl(Wire.TYPE to Wire.PAUSE, Wire.ON to "yes"))
        a.send(Wire.pause(false))
        a.send(Wire.pause(true))
        assertEquals(mapOf("t" to "pause", "on" to "1"), a.ctrl())
    }

    @Test
    fun nothingIsUploadedWhileCarSpeakerPlays() {
        val a = joined()
        sink.gate = CountDownLatch(1)
        a.say("s1", ByteArray(4))
        waitFor { server.speakerBusy() }
        repeat(2) { server.carPcm(pcm(it), it.toLong()) }
        sink.gate.countDown()
        waitFor { !server.speakerBusy() }
        server.carPcm(pcm(7), 7)
        val frames = (1..6).map { a.next() }
        val chunks = frames.filter { it.type == FrameType.CAR_PCM }.map { CarChunk.parse(it.body)!!.seq }
        assertEquals(listOf(2L), chunks)
        assertEquals(UploadGate.Counters(3, 1, 0, 0, 2, 0, 0), server.uploadCounters())
    }

    @Test
    fun silencedMicIsAnnouncedAndDropped() {
        val a = joined()
        server.setMicSilenced(true)
        assertEquals(mapOf("t" to "mic", "silenced" to "1", "input" to "0"), a.ctrl())
        server.carPcm(pcm(0), 0)
        server.setMicSilenced(false)
        assertEquals(mapOf("t" to "mic", "silenced" to "0", "input" to "0"), a.ctrl())
        server.carPcm(pcm(1), 1)
        assertEquals(1L, a.chunk().seq)
        assertEquals(1L, server.uploadCounters().silenced)
    }

    // R-28: 내비 안내 중에는 차 마이크 소리를 올리지 않고, 연결된 폰과 늦게 붙은 폰 모두 안다
    @Test
    fun navIsAnnouncedToPhonesAndLateJoinersAndDropsUpload() {
        val a = joined()
        server.setNav(true)
        assertEquals(mapOf("t" to "nav", "on" to "1"), a.ctrl())
        server.setNav(true)
        server.carPcm(pcm(0), 0)
        val b = hello("token" to server.token(System.currentTimeMillis()))
        assertEquals("welcome", b.ctrl()["t"])
        b.ctrl()
        b.ctrl()
        assertEquals(mapOf("t" to "nav", "on" to "1"), b.ctrl())
        server.setNav(false)
        assertEquals(mapOf("t" to "nav", "on" to "0"), a.ctrl())
        server.carPcm(pcm(1), 1)
        assertEquals(1L, a.chunk().seq)
        assertEquals(1L, server.uploadCounters().nav)
        assertFalse(server.nav())
    }

    @Test
    fun clockPingIsAnsweredWithCarTime() {
        val a = joined()
        val before = System.currentTimeMillis()
        a.send(Wire.clockPing())
        val m = a.ctrl()
        assertEquals("clock", m["t"])
        assertTrue(m["now"]!!.toLong() in before..System.currentTimeMillis())
    }

    @Test
    fun stoppedServerDropsAudio() {
        joined()
        server.stop()
        server.carPcm(pcm(0), 0)
        assertEquals(0L, server.uploadCounters().captured)
    }

    @Test
    fun micInputStateIsAnnouncedToPhonesAndLateJoiners() {
        val a = joined()
        server.setMicInput(true)
        assertEquals(mapOf("t" to "mic", "silenced" to "0", "input" to "1"), a.ctrl())
        server.setMicInput(true)
        server.setMicInput(false)
        assertEquals(mapOf("t" to "mic", "silenced" to "0", "input" to "0"), a.ctrl())
        server.setMicInput(true)
        assertEquals("1", a.ctrl()["input"])
        val b = hello("token" to server.token(System.currentTimeMillis()))
        assertEquals("welcome", b.ctrl()["t"])
        assertEquals(mapOf("t" to "mic", "silenced" to "0", "input" to "1"), b.ctrl())
    }

    // H-1: 받지 않는 폰이 있어도 캡처 호출은 바로 돌아오고, 다른 폰은 빠짐없이 받고, 재생 보고도 막히지 않는다
    @Test
    fun slowPhoneDoesNotBlockCaptureOtherPhonesOrSpeaker() {
        // 빨리 받는 폰 쪽 줄은 넉넉히(스레드 스케줄 지연으로 버리지 않게). 받지 않는 폰은 그래도 넘친다
        restart(audioBacklog = 200)
        val slow = stalled()
        val b = joined()
        waitFor { server.phones() == 2 }
        val drain = Drain(b)
        val seqs = drain.seqs
        val ctrls = drain.ctrls
        val n = 600 // 3,200B × 600 ≈ 1.9MB: 받지 않는 폰의 소켓 버퍼를 넘친다
        var slowest = 0L
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            server.carPcm(pcm(i), i.toLong())
            slowest = maxOf(slowest, System.nanoTime() - t0)
            if (i == n / 2) b.say("s1", ByteArray(4))
            Thread.sleep(1)
        }
        assertTrue("slowest carPcm ${slowest / 1_000_000}ms", slowest < 50_000_000L)
        waitFor(5_000) { mapOf("t" to "said", "id" to "s1", "state" to "done") in ctrls.toList() }
        waitFor(5_000) { seqs.lastOrNull() == (n - 1).toLong() }
        val sent = seqs.toList()
        val expected = (0 until n).map { it.toLong() }.filter { it !in sent }
        // 재생 중(반이중)인 동안만 빈다. 그 밖에 B가 잃은 청크는 없다
        assertEquals(server.uploadCounters().speaker, expected.size.toLong())
        assertEquals(sent.sorted(), sent)
        slow.socket.close()
        waitFor { logs.any { it.contains("audio dropped=") } }
    }

    // H-1: 쓰기가 막힌 채 시간이 지나면 그 연결을 닫아 정원·업로드 대상에서 뺀다
    @Test
    fun phoneThatStopsReadingIsClosedAfterWriteTimeout() {
        restart(writeTimeoutMs = 300)
        val slow = stalled()
        waitFor { server.phones() == 1 }
        repeat(600) {
            server.carPcm(pcm(it), it.toLong())
            Thread.sleep(1)
        }
        waitFor(5_000) { server.phones() == 0 }
        assertTrue(logs.toString(), logs.any { it.contains("write blocked") })
        slow.socket.close()
    }

    // H-2: 받지 않는 폰이 있어도 끄기가 짧게 끝나고, 다른 폰은 off를 받고, 모든 소켓·포트가 닫힌다
    @Test
    fun stopFinishesQuicklyWithAStalledPhone() {
        val slow = stalled()
        val b = joined()
        waitFor { server.phones() == 2 }
        val drain = Drain(b)
        repeat(600) {
            server.carPcm(pcm(it), it.toLong())
            Thread.sleep(1)
        }
        val t0 = System.currentTimeMillis()
        server.stop()
        val took = System.currentTimeMillis() - t0
        assertTrue("stop took ${took}ms", took < 2_000)
        drain.thread.join(3_000)
        assertFalse(drain.thread.isAlive)
        assertEquals(mapOf("t" to "off"), drain.ctrls.last())
        assertTrue(logs.toString(), logs.any { it.contains("off not confirmed=1") })
        assertEquals(0, server.phones())
        slow.socket.soTimeout = 5_000
        val drained = try {
            while (FrameCodec.read(slow.input) != null) Unit
            true
        } catch (_: IOException) {
            true
        }
        assertTrue(drained)
        assertThrows(IOException::class.java) {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }
    }

    // H-3: 켜져 있는 동안 차가 주기적으로 ping을 보낸다
    @Test
    fun carPingsConnectedPhones() {
        restart(pingMs = 100)
        val a = joined()
        assertEquals(mapOf("t" to "ping"), a.ctrl())
        assertEquals(mapOf("t" to "ping"), a.ctrl())
    }

    // H-3: 아무것도 보내지 않는 폰(유령 연결)은 끊고 정원을 돌려준다. ping을 보내는 폰은 남는다
    @Test
    fun silentPhoneIsDroppedAndFreesCapacity() {
        restart(pingMs = 100, peerTimeoutMs = 400)
        joined()
        val live = joined()
        waitFor { server.phones() == 2 }
        val pinger = Thread {
            try {
                while (true) {
                    live.send(Wire.ping())
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        waitFor { server.phones() == 1 }
        assertTrue(logs.toString(), logs.any { it.contains("SocketTimeoutException") })
        Thread.sleep(600)
        assertEquals(1, server.phones())
        assertEquals("welcome", hello("token" to server.token(System.currentTimeMillis())).ctrl()["t"])
        pinger.interrupt()
    }

    // M-1: HELLO를 조금씩 보내며 버텨도 전체 마감(1초)에 닫힌다
    @Test
    fun trickledHelloIsClosedAtTotalDeadline() {
        val c = Client()
        val bytes = byteArrayOf(1, 0, 0, 3, 0) + ByteArray(768)
        val t0 = System.currentTimeMillis()
        val trickle = Thread {
            try {
                for (b in bytes) {
                    c.sendRaw(byteArrayOf(b))
                    Thread.sleep(200)
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        c.assertClosed()
        val took = System.currentTimeMillis() - t0
        assertTrue("closed after ${took}ms", took < 2_500)
        trickle.interrupt()
    }

    // 캡처 스레드가 보내는 동안 폰이 붙고 떨어져도 carPcm이 예외를 던지지 않는다(연결 목록 스냅샷 경쟁)
    @Test
    fun captureSurvivesPhonesComingAndGoing() {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = AtomicBoolean(false)
        val capture = Thread {
            var i = 0
            while (!stop.get()) {
                try {
                    server.carPcm(pcm(i), i.toLong())
                } catch (t: Throwable) {
                    failure.set(t)
                    return@Thread
                }
                i++
            }
        }.apply { isDaemon = true; start() }
        repeat(100) {
            val c = hello("token" to server.token(System.currentTimeMillis()))
            assertEquals("welcome", c.ctrl()["t"])
            c.socket.close()
            waitFor { server.phones() == 0 }
        }
        stop.set(true)
        capture.join(2_000)
        assertNull(failure.get())
    }

    private fun said(id: String, state: String, reason: String? = null) =
        if (reason == null) mapOf("t" to "said", "id" to id, "state" to state) else mapOf("t" to "said", "id" to id, "state" to state, "reason" to reason)

    private fun Drain.has(m: Map<String, String>) = m in ctrls.toList()

    private fun Drain.shared() = ctrls.toList().filter { it["t"] == "shared" }

    private fun Drain.count(m: Map<String, String>) = ctrls.toList().count { it == m }

    private val speakerOn = mapOf("t" to "speaker", "on" to "1")
    private val speakerOff = mapOf("t" to "speaker", "on" to "0")

    /** 보낸 폰이 마지막 speaker off를 받고, 다른 폰도 같은 수의 off를 받을 때까지(그 앞에 넣은 shared까지 다 받은 뒤) 기다린다. */
    private fun settle(sender: Drain, vararg others: Drain) {
        waitFor { sender.ctrls.lastOrNull { it["t"] == "speaker" } == speakerOff }
        val n = sender.count(speakerOff)
        others.forEach { d -> waitFor { d.count(speakerOff) == n } }
    }

    // 단톡방식: 보낸 폰을 뺀 모든 폰이 읽기 시작할 때 한 번 받는다. 로그에는 글 내용이 없다
    @Test
    fun sharedTextGoesOnceToEveryOtherPhoneWhenPlayingStartsAndIsNotLogged() {
        restart(phones = 3)
        val text = "창문 조금만 \"열어\" 주세요 비밀7Q"
        val ac = joined()
        val a = Drain(ac)
        val bc = listOf(Drain(joined()), Drain(joined()))
        waitFor { server.phones() == 3 }
        ac.say("s1", ByteArray(4), text = text)
        waitFor { a.has(said("s1", "done")) }
        settle(a, *bc.toTypedArray())
        bc.forEach { d ->
            assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to text)), d.shared())
            val l = d.ctrls.toList()
            assertTrue(l.toString(), l.indexOf(speakerOn) < l.indexOfFirst { it["t"] == "shared" })
        }
        assertTrue(a.shared().isEmpty())
        assertTrue(logs.toString(), logs.any { it.contains("say id=s1 shared to=2 chars=${text.length}") })
        assertTrue(logs.none { it.contains("비밀7Q") || it.contains("창문") })
    }

    @Test
    fun failedSaysAreNotShared() {
        val a = joined()
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        calling.set(true)
        a.say("s1", ByteArray(4), text = "통화 중 글")
        val first = listOf(a.ctrl(), a.ctrl())
        assertTrue(first.toString(), said("s1", "failed", "call") in first)
        a.say("s2", ByteArray(0), text = "빈 소리")
        assertEquals(said("s2", "failed", "empty"), a.ctrl())
        a.send(Wire.say("s3", 1_000, "형식 틀림"))
        assertEquals(said("s3", "failed", "format"), a.ctrl())
        calling.set(false)
        a.say("s4", ByteArray(4), text = "확인")
        waitFor { b.shared().isNotEmpty() }
        assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to "확인")), b.shared())
    }

    @Test
    fun sayCancelledWhileWaitingForNavigationIsNotShared() {
        val a = joined()
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        navOn.set(true)
        a.say("s1", ByteArray(4), text = "내비 끝나면")
        val seen = listOf(a.ctrl(), a.ctrl())
        assertTrue(seen.toString(), said("s1", "waiting", "nav") in seen)
        server.stop()
        waitFor { b.has(mapOf("t" to "off")) }
        assertTrue(b.shared().isEmpty())
    }

    @Test
    fun replayAfterNavigationInterruptionSharesOnlyOnce() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.script += { audible ->
            audible()
            navOn.set(true)
            PlayResult.YIELDED
        }
        ac.say("s1", ByteArray(4), text = "다시 읽어도 한 번")
        waitFor { a.has(said("s1", "waiting", "nav")) }
        navOn.set(false)
        waitFor { a.has(said("s1", "done")) }
        settle(a, b)
        assertEquals(2, sink.played.count { it.first == "s1" })
        assertEquals(2, a.count(said("s1", "playing")))
        assertEquals(2, b.count(speakerOn))
        assertEquals(1, b.shared().size)
    }

    /** 앞 글이 공유되지 않았다면 이어 보낸 글이 공유 번호 1번으로 먼저 도착한다. */
    private fun Client.assertNothingSharedBefore(b: Drain) {
        say("check", ByteArray(4), text = "확인")
        waitFor { b.shared().isNotEmpty() }
        assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to "확인")), b.shared())
    }

    // 포커스 거절: 보낸 폰의 playing은 그대로 나가지만 소리가 나지 않았으므로 공유하지 않는다
    @Test
    fun focusRefusedIsNotShared() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.script += { PlayResult.YIELDED }
        ac.say("s1", ByteArray(4), text = "한 글자도 못 읽음")
        waitFor { a.has(said("s1", "failed", "focus")) }
        assertTrue(a.has(said("s1", "playing")))
        ac.assertNothingSharedBefore(b)
    }

    // 시작 직후 양보(소리 전에 통화 시작)
    @Test
    fun immediateYieldToCallIsNotShared() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.script += {
            calling.set(true)
            PlayResult.YIELDED
        }
        ac.say("s1", ByteArray(4), text = "통화에 양보")
        waitFor { a.has(said("s1", "failed", "call")) }
        calling.set(false)
        ac.assertNothingSharedBefore(b)
    }

    // 시작 직후 양보(소리 전에 내비 시작): 기다리는 동안은 공유하지 않고, 실제로 읽을 때 한 번 공유한다
    @Test
    fun immediateYieldToNavigationSharesOnlyWhenReplayMakesSound() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.script += {
            navOn.set(true)
            PlayResult.YIELDED
        }
        ac.say("s1", ByteArray(4), text = "내비 뒤에 읽음")
        waitFor { a.has(said("s1", "waiting", "nav")) }
        assertTrue(logs.toString(), logs.none { it.contains("say id=s1 shared") })
        navOn.set(false)
        waitFor { a.has(said("s1", "done")) }
        settle(a, b)
        assertEquals(2, sink.played.count { it.first == "s1" })
        assertEquals(listOf("내비 뒤에 읽음"), b.shared().map { it["text"] })
    }

    @Test
    fun queueOverflowIsNotShared() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.gate = CountDownLatch(1)
        ac.say("s0", ByteArray(4), text = "글0")
        waitFor { sink.played.isNotEmpty() }
        (1..SpeakerQueue.MAX_WAITING).forEach { ac.say("s$it", ByteArray(4), text = "글$it") }
        waitFor { (1..SpeakerQueue.MAX_WAITING).all { a.has(said("s$it", "queued")) } }
        ac.say("over", ByteArray(4), text = "넘침")
        waitFor { a.has(said("over", "failed", "busy")) }
        sink.gate.countDown()
        waitFor { (0..SpeakerQueue.MAX_WAITING).all { a.has(said("s$it", "done")) } }
        waitFor { b.shared().size == SpeakerQueue.MAX_WAITING + 1 }
        settle(a, b)
        assertEquals((0..SpeakerQueue.MAX_WAITING).map { "글$it" }, b.shared().map { it["text"] })
    }

    // 소리가 난 뒤 실패한 글은 이미 공유되어 있고, 다시 보내거나 되돌리지 않는다(ADR-0004 결정 8)
    @Test
    fun audibleThenFailureIsSharedExactlyOnce() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.script += { audible ->
            audible()
            PlayResult.FAILED
        }
        sink.script += { audible ->
            audible()
            PlayResult.YIELDED
        }
        ac.say("s1", ByteArray(4), text = "읽다 실패")
        waitFor { a.has(said("s1", "failed", "playback")) }
        ac.say("s2", ByteArray(4), text = "읽다 포커스 잃음")
        waitFor { a.has(said("s2", "failed", "focus")) }
        settle(a, b)
        assertEquals(listOf("읽다 실패", "읽다 포커스 잃음"), b.shared().map { it["text"] })
    }

    @Test
    fun oldFormatBlankAndOverLongTextStillPlayButAreNotShared() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        ac.say("s1", ByteArray(4))
        ac.say("s2", ByteArray(4), text = " \n ")
        ac.say("s3", ByteArray(4), text = "가".repeat(Wire.SAY_TEXT_MAX_CHARS + 1))
        waitFor { listOf("s1", "s2", "s3").all { a.has(said(it, "done")) } }
        settle(a, b)
        assertTrue(b.shared().isEmpty())
        assertTrue(logs.toString(), logs.any { it.contains("say id=s1 received") && it.contains("chars=0") })
    }

    // 보낸 폰이 끊겼다 재접속(새 연결·새 키)해도 자기 글을 "다른 동승자" 글로 받지 않는다
    @Test
    fun senderThatRejoinedDoesNotGetItsOwnText() {
        val a = joined()
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.gate = CountDownLatch(1)
        a.say("s0", ByteArray(4))
        a.say("s1", ByteArray(4), text = "재접속한 폰의 글")
        val seen = (1..4).map { a.ctrl() }
        assertTrue(seen.toString(), said("s1", "queued") in seen)
        a.socket.close()
        waitFor { server.phones() == 1 }
        val a2 = hello("key" to a.key!!)
        assertEquals("welcome", a2.ctrl()["t"])
        val a2d = Drain(a2)
        waitFor { server.phones() == 2 }
        sink.gate.countDown()
        waitFor { b.shared().isNotEmpty() }
        // a2는 환영 때 speaker on을 받았으므로 off는 재생이 모두 끝난 뒤 한 번만 온다
        waitFor { a2d.ctrls.lastOrNull() == speakerOff }
        assertEquals(listOf("재접속한 폰의 글"), b.shared().map { it["text"] })
        assertTrue(a2d.ctrls.toString(), a2d.shared().isEmpty())
    }

    private fun Drain.saw(t: String) = ctrls.toList().any { it["t"] == t }

    // 소리 없이 보내기: 보낸 폰을 뺀 모든 폰에 가고, 스피커는 전혀 움직이지 않고, 로그에는 글 내용이 없다
    @Test
    fun noteGoesToEveryOtherPhoneButNotSenderNorSpeakerAndIsNotLogged() {
        restart(phones = 3)
        val text = "뒷자리 창문 \"닫아\" 줘 비밀8Z"
        val a = joined()
        val others = listOf(Drain(joined()), Drain(joined()))
        waitFor { server.phones() == 3 }
        a.send(Wire.note("n1", text))
        assertEquals(said("n1", "done"), a.ctrl())
        others.forEach { d ->
            waitFor { d.shared().isNotEmpty() }
            assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to text)), d.shared())
            assertFalse(d.ctrls.toString(), d.saw("speaker"))
        }
        assertTrue(sink.played.isEmpty())
        assertFalse(server.speakerBusy())
        a.socket.soTimeout = 200
        assertThrows(IOException::class.java) { a.next() }
        assertTrue(logs.toString(), logs.any { it.contains("note id=n1 shared to=2 chars=${text.length}") })
        assertTrue(logs.none { it.contains("비밀8Z") || it.contains("창문") })
    }

    @Test
    fun noteWithNoOtherPhoneFailsWithNobodyAndUsesNoNumber() {
        val a = joined()
        a.send(Wire.note("n1", "혼자 있음"))
        assertEquals(said("n1", "failed", "nobody"), a.ctrl())
        assertTrue(logs.toString(), logs.any { it.contains("note id=n1 shared to=0 chars=5") })
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        a.send(Wire.note("n2", "이제 있음"))
        assertEquals(said("n2", "done"), a.ctrl())
        waitFor { b.shared().isNotEmpty() }
        assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to "이제 있음")), b.shared())
    }

    // 대화명은 보낸 폰의 글과 함께 다른 폰으로 가고, 맞지 않는 값은 없는 것으로 친다. 로그에는 남기지 않는다
    @Test
    fun sharedCarriesSenderNameAndBadNameIsDropped() {
        val named = hello("token" to server.token(System.currentTimeMillis()), "name" to "지안")
        assertEquals("welcome", named.ctrl()["t"])
        val badName = hello("token" to server.token(System.currentTimeMillis()), "name" to " ")
        assertEquals("welcome", badName.ctrl()["t"])
        waitFor { server.phones() == 2 }
        val toNamed = Drain(named)
        val toBad = Drain(badName)
        named.send(Wire.note("n1", "저 지안"))
        waitFor { toBad.shared().size == 1 }
        badName.send(Wire.note("n2", "이름 없음"))
        waitFor { toNamed.shared().size == 1 }
        assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to "저 지안", "name" to "지안")), toBad.shared())
        assertEquals(listOf(mapOf("t" to "shared", "id" to "2", "text" to "이름 없음")), toNamed.shared())
        assertTrue(logs.toString(), logs.none { it.contains("지안") })
    }

    // 끊겼다 재접속한 폰은 자기 글을 받지 않고, 다른 폰으로 세지도 않는다
    @Test
    fun rejoinedSenderIsNotItsOwnReceiver() {
        val a = joined()
        a.socket.close()
        waitFor { server.phones() == 0 }
        val a2 = hello("key" to a.key!!)
        assertEquals("welcome", a2.ctrl()["t"])
        repeat(3) { a2.ctrl() }
        waitFor { server.phones() == 1 }
        a2.send(Wire.note("n1", "나뿐"))
        assertEquals(said("n1", "failed", "nobody"), a2.ctrl())
    }

    // 통화·내비 안내 중에도, 다른 글을 읽는 중에도 기다리지 않고 바로 간다
    @Test
    fun noteGoesThroughDuringCallAndNavigationWithoutTouchingSpeaker() {
        val a = joined()
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        calling.set(true)
        navOn.set(true)
        server.setNav(true)
        assertEquals(mapOf("t" to "nav", "on" to "1"), a.ctrl())
        a.send(Wire.note("n1", "통화·내비 중"))
        assertEquals(said("n1", "done"), a.ctrl())
        waitFor { b.shared().size == 1 }
        assertEquals("통화·내비 중", b.shared()[0]["text"])
        assertTrue(sink.played.isEmpty())
        assertFalse(server.speakerBusy())
        assertFalse(b.ctrls.toString(), b.saw("speaker"))
    }

    @Test
    fun noteGoesThroughWhileSpeakerPlaysAndDoesNotJoinTheQueue() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        sink.gate = CountDownLatch(1)
        ac.say("s1", ByteArray(4))
        waitFor { sink.played.size == 1 }
        ac.send(Wire.note("n1", "읽는 중"))
        waitFor { a.has(said("n1", "done")) }
        waitFor { b.shared().size == 1 }
        assertTrue(server.speakerBusy())
        assertFalse(a.has(said("s1", "done")))
        sink.gate.countDown()
        waitFor { a.has(said("s1", "done")) }
        settle(a, b)
        assertEquals(listOf("s1"), sink.played.map { it.first })
    }

    // say처럼 자막 멈춤·주행 중에도 된다. 차 마이크 업로드 조건은 건드리지 않는다
    @Test
    fun noteIsAllowedWhilePausedAndDrivingLikeSay() {
        val a = joined()
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        driving.set(true)
        a.send(Wire.pause(true))
        assertEquals(mapOf("t" to "pause", "on" to "1"), a.ctrl())
        a.send(Wire.note("n1", "멈춤·주행 중"))
        assertEquals(said("n1", "done"), a.ctrl())
        waitFor { b.shared().size == 1 }
        assertTrue(server.paused())
        server.carPcm(pcm(0), 0)
        assertEquals(UploadGate.Counters(1, 0, 0, 1, 0, 0, 0), server.uploadCounters())
    }

    @Test
    fun invalidNoteTextFailsWithFormatAndInvalidIdIsIgnoredAndNeitherIsShared() {
        val a = joined()
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        a.send(Wire.note("blank", " \n "))
        assertEquals(said("blank", "failed", "format"), a.ctrl())
        a.send(Wire.note("long", "가".repeat(Wire.SAY_TEXT_MAX_CHARS + 1)))
        assertEquals(said("long", "failed", "format"), a.ctrl())
        a.send(Wire.ctrl(Wire.TYPE to Wire.NOTE, Wire.ID to "none"))
        assertEquals(said("none", "failed", "format"), a.ctrl())
        a.send(Wire.note("bad id", "번호 틀림"))
        a.send(Wire.ctrl(Wire.TYPE to Wire.NOTE, Wire.TEXT to "번호 없음"))
        a.send(Wire.note("ok", "맞는 글"))
        assertEquals(said("ok", "done"), a.ctrl())
        waitFor { b.shared().isNotEmpty() }
        assertEquals(listOf(mapOf("t" to "shared", "id" to "1", "text" to "맞는 글")), b.shared())
        assertEquals(2, server.phones())
        assertTrue(logs.none { it.contains("번호") || it.contains("맞는 글") })
    }

    @Test
    fun noteAndSayShareTheSameNumbering() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        ac.say("s1", ByteArray(4), text = "소리 1")
        waitFor { b.shared().size == 1 }
        ac.send(Wire.note("n1", "글 2"))
        waitFor { b.shared().size == 2 }
        ac.say("s2", ByteArray(4), text = "소리 3")
        waitFor { a.has(said("s2", "done")) }
        settle(a, b)
        assertEquals(listOf("1", "2", "3"), b.shared().map { it["id"] })
        assertEquals(listOf("소리 1", "글 2", "소리 3"), b.shared().map { it["text"] })
    }

    // say 프레임 사이에 note가 끼어도 문장 조립이 끊기지 않는다
    @Test
    fun noteBetweenSayFramesLeavesTheUtteranceIntact() {
        val ac = joined()
        val a = Drain(ac)
        val b = Drain(joined())
        waitFor { server.phones() == 2 }
        ac.send(Wire.say("s1", 24_000))
        ac.send(Wire.pcm(ByteArray(4)))
        ac.send(Wire.note("n1", "끼어든 글"))
        ac.send(Wire.pcm(ByteArray(4)))
        ac.send(Wire.sayEnd("s1"))
        waitFor { a.has(said("n1", "done")) && a.has(said("s1", "done")) }
        settle(a, b)
        assertEquals(listOf("s1" to Pcm.chime(24_000).size + 8), sink.played.toList())
    }
}
