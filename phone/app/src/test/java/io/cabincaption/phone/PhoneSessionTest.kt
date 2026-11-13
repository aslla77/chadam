package io.cabincaption.phone

import io.cabincaption.phone.PhoneSession.End
import io.cabincaption.phone.PhoneSession.Status
import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.Ctrl
import io.cabincaption.protocol.Frame
import io.cabincaption.protocol.FrameCodec
import io.cabincaption.protocol.FrameType
import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSessionTest {

    private val token = "AbCdEfGhIjKlMnOpQrSt_-"
    private val car = ServerSocket(0)
    private val events = LinkedBlockingQueue<String>()
    private var session: PhoneSession? = null

    @After
    fun tearDown() {
        session?.leave()
        car.close()
    }

    private inner class Recorder : PhoneSession.Listener {
        override fun onStatus(status: Status) {
            events += "status:$status"
        }
        override fun onEnd(end: End, reason: RejectReason?) {
            events += "end:$end:${reason?.wire}"
        }
        override fun onSaid(id: String, state: SayState, reason: String?) {
            events += "said:$id:${state.wire}${reason?.let { ":$it" } ?: ""}"
        }
        override fun onNav(on: Boolean) {
            events += "nav:$on"
        }
        override fun onSpeaker(on: Boolean) {
            events += "speaker:$on"
        }
        override fun onCarPcm(chunk: CarChunk, rxMs: Long) {
            events += "pcm:${chunk.seq}:${chunk.captureMs}:${chunk.pcm.size}"
            rxTimes += rxMs
        }
        override fun onPause(on: Boolean) {
            events += "pause:$on"
        }
        override fun onCarMic(silenced: Boolean, input: Boolean) {
            events += "mic:$silenced:$input"
        }
        override fun onClock(offsetMs: Long, rttMs: Long) {
            events += "clock"
            clock = offsetMs to rttMs
        }
        override fun onShared(id: String, text: String, name: String?) {
            events += if (name == null) "shared:$id:$text" else "shared:$id:$text:$name"
        }
    }

    private val rxTimes = LinkedBlockingQueue<Long>()
    @Volatile private var clock: Pair<Long, Long>? = null

    /** 차 쪽 한 연결. */
    private inner class Peer(val s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        init {
            s.soTimeout = 3_000
        }
        fun read(): Frame = FrameCodec.read(input)!!
        fun hello(): Map<String, String> = read().let {
            assertEquals(FrameType.HELLO, it.type)
            Ctrl.decode(it.body)
        }
        fun send(f: Frame) = FrameCodec.write(s.getOutputStream(), f)
        fun welcome(key: String = "K".repeat(22)) = send(Wire.ctrl(Wire.TYPE to Wire.WELCOME, Wire.KEY to key))
    }

    private fun accept(): Peer = Peer(car.accept())

    /** 기본은 ping·끊김 감시가 끼어들지 않게 길게 둔다. */
    private fun start(retries: List<Long> = listOf(50L, 50L), pingMs: Long = 60_000, peerTimeoutMs: Int = 60_000): PhoneSession =
        PhoneSession(
            JoinUri("127.0.0.1", car.localPort, token), Recorder(), connectTimeoutMs = 1_000, welcomeTimeoutMs = 1_000, retryDelaysMs = retries,
            pingMs = pingMs, peerTimeoutMs = peerTimeoutMs,
        ).also { session = it; it.start() }

    private fun next(): String = events.poll(3, TimeUnit.SECONDS) ?: error("no event")

    private fun connected(pingMs: Long = 60_000, peerTimeoutMs: Int = 60_000): Peer {
        start(pingMs = pingMs, peerTimeoutMs = peerTimeoutMs)
        assertEquals("status:CONNECTING", next())
        val p = accept()
        assertEquals(mapOf("token" to token), p.hello())
        p.welcome()
        assertEquals("status:CONNECTED", next())
        assertEquals(mapOf("t" to "clock"), Ctrl.decode(p.read().body))
        return p
    }

    @Test
    fun joinsWithTokenAndGetsCarEvents() {
        val p = connected()
        p.send(Wire.speaker(true))
        p.send(Wire.said("s1", SayState.PLAYING))
        assertEquals("speaker:true", next())
        assertEquals("said:s1:playing", next())
    }

    @Test
    fun saidReasonAndNavArePassedOnAndBadNavIgnored() {
        val p = connected()
        p.send(Wire.said("s1", SayState.WAITING, Wire.SAID_NAV))
        p.send(Wire.nav(true))
        p.send(Wire.ctrl(Wire.TYPE to Wire.NAV, Wire.ON to "yes"))
        p.send(Wire.said("s1", SayState.FAILED, Wire.SAID_CALL))
        p.send(Wire.nav(false))
        assertEquals("said:s1:waiting:nav", next())
        assertEquals("nav:true", next())
        assertEquals("said:s1:failed:call", next())
        assertEquals("nav:false", next())
    }

    @Test
    fun eachRejectReasonEndsWithReason() {
        for (r in RejectReason.entries) {
            events.clear()
            start()
            assertEquals("status:CONNECTING", next())
            val p = accept()
            p.hello()
            p.send(Wire.ctrl(Wire.TYPE to Wire.REJECT, Wire.REASON to r.wire))
            p.s.close()
            assertEquals("end:REJECTED:${r.wire}", next())
        }
    }

    @Test
    fun unreachableCarEnds() {
        val port = car.localPort
        car.close()
        PhoneSession(JoinUri("127.0.0.1", port, token), Recorder(), connectTimeoutMs = 500).also { session = it; it.start() }
        assertEquals("status:CONNECTING", next())
        assertEquals("end:UNREACHABLE:null", next())
    }

    @Test
    fun offEndsSession() {
        val p = connected()
        p.send(Wire.ctrl(Wire.TYPE to Wire.OFF))
        assertEquals("speaker:false", next())
        assertEquals("end:CAR_OFF:null", next())
    }

    @Test
    fun dropReconnectsWithKey() {
        val p = connected()
        p.s.close()
        assertEquals("speaker:false", next())
        assertEquals("status:RECONNECTING", next())
        val p2 = accept()
        assertEquals(mapOf("key" to "K".repeat(22)), p2.hello())
        p2.welcome()
        assertEquals("status:CONNECTED", next())
    }

    @Test
    fun rejectedReconnectIsLost() {
        val p = connected()
        p.s.close()
        next()
        next()
        val p2 = accept()
        p2.hello()
        p2.send(Wire.ctrl(Wire.TYPE to Wire.REJECT, Wire.REASON to "invalid"))
        assertEquals("end:LOST:invalid", next())
    }

    @Test
    fun retriesRunOutThenLost() {
        val p = connected()
        car.close()
        p.s.close()
        assertEquals("speaker:false", next())
        assertEquals("status:RECONNECTING", next())
        assertEquals("status:RECONNECTING", next())
        assertEquals("end:LOST:null", next())
    }

    @Test
    fun sayWritesStartChunksEnd() {
        val p = connected()
        val pcm = ByteArray(Wire.PCM_CHUNK * 2 + 10) { it.toByte() }
        assertTrue(session!!.say("s1", 24_000, pcm))
        assertEquals(mapOf("t" to "say", "id" to "s1", "rate" to "24000"), Ctrl.decode(p.read().body))
        var got = ByteArray(0)
        repeat(3) {
            val f = p.read()
            assertEquals(FrameType.PHONE_PCM, f.type)
            got += f.body
        }
        assertArrayEquals(pcm, got)
        assertEquals(mapOf("t" to "say_end", "id" to "s1"), Ctrl.decode(p.read().body))
    }

    @Test
    fun sayWithTextSendsItInTheStartMessage() {
        val p = connected()
        assertTrue(session!!.say("s2", 24_000, ByteArray(2), "에어컨 줄여 주세요"))
        assertEquals(mapOf("t" to "say", "id" to "s2", "rate" to "24000", "text" to "에어컨 줄여 주세요"), Ctrl.decode(p.read().body))
        assertEquals(FrameType.PHONE_PCM, p.read().type)
        assertEquals(mapOf("t" to "say_end", "id" to "s2"), Ctrl.decode(p.read().body))
    }

    @Test
    fun noteSendsOnlyTheTextAndResultComesBackAsSaid() {
        val p = connected()
        assertTrue(session!!.note("s3", "뒷자리 창문 닫아 줘"))
        assertEquals(mapOf("t" to "note", "id" to "s3", "text" to "뒷자리 창문 닫아 줘"), Ctrl.decode(p.read().body))
        p.send(Wire.said("s3", SayState.DONE))
        p.send(Wire.said("s4", SayState.FAILED, Wire.SAID_NOBODY))
        assertEquals("said:s3:done", next())
        assertEquals("said:s4:failed:nobody", next())
    }

    @Test
    fun noteBeforeWelcomeFails() {
        start()
        next()
        val p = accept()
        p.hello()
        assertFalse(session!!.note("s1", "환영 전"))
    }

    // 끊긴 동안은 false(화면은 연결 없음으로 실패), 다시 붙으면 다시 보낼 수 있다
    @Test
    fun noteFailsWhileDroppedAndWorksAfterReconnect() {
        val p = connected()
        p.s.close()
        assertEquals("speaker:false", next())
        assertFalse(session!!.note("s1", "끊긴 동안"))
        assertEquals("status:RECONNECTING", next())
        val p2 = accept()
        p2.hello()
        p2.welcome()
        assertEquals("status:CONNECTED", next())
        assertEquals(mapOf("t" to "clock"), Ctrl.decode(p2.read().body))
        assertTrue(session!!.note("s2", "다시 붙음"))
        assertEquals(mapOf("t" to "note", "id" to "s2", "text" to "다시 붙음"), Ctrl.decode(p2.read().body))
    }

    @Test
    fun sharedTextIsPassedOnAndMalformedOnesAreIgnored() {
        val p = connected()
        p.send(Wire.ctrl(Wire.TYPE to Wire.SHARED, Wire.ID to "1"))
        p.send(Wire.shared("2", " "))
        p.send(Wire.shared("3", "가".repeat(Wire.SAY_TEXT_MAX_CHARS + 1)))
        p.send(Wire.shared("has space", "네"))
        p.send(Wire.ctrl(Wire.TYPE to Wire.SHARED, Wire.TEXT to "id 없음"))
        p.send(Wire.shared("4", "다음 휴게소에서 쉬어요"))
        assertEquals("shared:4:다음 휴게소에서 쉬어요", next())
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun sharedNameIsPassedOnAndBadNameIsDropped() {
        val p = connected()
        p.send(Wire.shared("1", "네", "지안"))
        p.send(Wire.shared("2", "네", "가".repeat(Wire.NAME_MAX_CHARS + 1)))
        assertEquals("shared:1:네:지안", next())
        assertEquals("shared:2:네", next())
    }

    @Test
    fun helloCarriesNameOnJoinAndRejoin() {
        session = PhoneSession(
            JoinUri("127.0.0.1", car.localPort, token), Recorder(), connectTimeoutMs = 1_000, welcomeTimeoutMs = 1_000,
            retryDelaysMs = listOf(50L, 50L), pingMs = 60_000, peerTimeoutMs = 60_000, name = "지안",
        ).also { it.start() }
        assertEquals("status:CONNECTING", next())
        val p = accept()
        assertEquals(mapOf("token" to token, "name" to "지안"), p.hello())
        p.welcome()
        assertEquals("status:CONNECTED", next())
        p.s.close()
        while (next() != "status:RECONNECTING") Unit
        val p2 = accept()
        assertEquals("지안", p2.hello()["name"])
    }

    @Test
    fun sharedBeforeWelcomeIsIgnored() {
        start()
        assertEquals("status:CONNECTING", next())
        val p = accept()
        p.hello()
        p.send(Wire.shared("1", "환영 전"))
        p.welcome()
        assertEquals("status:CONNECTED", next())
        p.send(Wire.shared("2", "환영 뒤"))
        assertEquals("shared:2:환영 뒤", next())
    }

    @Test
    fun sayBeforeWelcomeFails() {
        start()
        next()
        val p = accept()
        p.hello()
        assertFalse(session!!.say("s1", 24_000, ByteArray(2)))
    }

    @Test
    fun leaveClosesAndStaysSilent() {
        val p = connected()
        session!!.leave()
        assertNull(FrameCodec.read(p.input))
        Thread.sleep(200)
        assertNull(events.poll())
    }

    @Test
    fun carAudioChunksArriveWithReceiveTime() {
        val p = connected()
        val before = System.currentTimeMillis()
        p.send(CarChunk(41, 123_456, ByteArray(CarChunk.CHUNK_BYTES)).frame())
        p.send(Frame(FrameType.CAR_PCM, ByteArray(CarChunk.HEADER))) // PCM 없음 → 버림
        p.send(CarChunk(42, 123_556, ByteArray(CarChunk.CHUNK_BYTES)).frame())
        assertEquals("pcm:41:123456:3200", next())
        assertEquals("pcm:42:123556:3200", next())
        assertTrue(rxTimes.poll()!! >= before)
    }

    @Test
    fun pauseAndMicStateEventsIgnoreBadValues() {
        val p = connected()
        p.send(Wire.ctrl(Wire.TYPE to Wire.PAUSE, Wire.ON to "x"))
        p.send(Wire.pause(true))
        p.send(Wire.ctrl(Wire.TYPE to Wire.MIC))
        p.send(Wire.ctrl(Wire.TYPE to Wire.MIC, Wire.SILENCED to "0"))
        p.send(Wire.mic(true, false))
        assertEquals("pause:true", next())
        assertEquals("mic:true:false", next())
    }

    @Test
    fun setPauseSendsRequest() {
        val p = connected()
        assertTrue(session!!.setPause(true))
        assertEquals(mapOf("t" to "pause", "on" to "1"), Ctrl.decode(p.read().body))
        assertTrue(session!!.setPause(false))
        assertEquals(mapOf("t" to "pause", "on" to "0"), Ctrl.decode(p.read().body))
    }

    @Test
    fun clockIsMeasuredWithFivePings() {
        val p = connected()
        repeat(ClockSync.SAMPLES) { i ->
            p.send(Wire.clockPong(System.currentTimeMillis() + 10_000))
            if (i < ClockSync.SAMPLES - 1) assertEquals(mapOf("t" to "clock"), Ctrl.decode(p.read().body))
        }
        assertEquals("clock", next())
        val (off, rtt) = clock!!
        assertTrue("off=$off rtt=$rtt", kotlin.math.abs(off - 10_000) <= rtt + 5)
        p.s.soTimeout = 300
        assertTrue(runCatching { p.read() }.isFailure) // 다 모으면 더 보내지 않는다
    }

    // H-3: 환영 뒤 폰이 주기적으로 ping을 보낸다(차가 살아 있는 폰을 구분)
    @Test
    fun phoneSendsPingsWhileConnected() {
        val p = connected(pingMs = 50)
        assertEquals(mapOf("t" to "ping"), Ctrl.decode(p.read().body))
        assertEquals(mapOf("t" to "ping"), Ctrl.decode(p.read().body))
    }

    // H-3: 차에서 아무것도 오지 않으면(유령 연결) 끊긴 것으로 보고 재접속 키로 다시 붙는다
    @Test
    fun silentCarIsTreatedAsDroppedAndReconnects() {
        connected(peerTimeoutMs = 300)
        assertEquals("speaker:false", next())
        assertEquals("status:RECONNECTING", next())
        val p2 = accept()
        assertEquals(mapOf("key" to "K".repeat(22)), p2.hello())
    }

    @Test
    fun carPingsKeepTheConnectionAlive() {
        val p = connected(peerTimeoutMs = 300)
        repeat(8) {
            p.send(Wire.ping())
            Thread.sleep(100)
        }
        assertNull(events.poll())
    }

    // M-2: 차는 재접속 때마다 새 키를 준다. 폰은 다음 재접속에 그 새 키를 쓴다
    @Test
    fun rotatedKeyFromWelcomeIsUsedOnNextReconnect() {
        val p = connected()
        p.s.close()
        assertEquals("speaker:false", next())
        assertEquals("status:RECONNECTING", next())
        val p2 = accept()
        assertEquals(mapOf("key" to "K".repeat(22)), p2.hello())
        p2.welcome("N".repeat(22))
        assertEquals("status:CONNECTED", next())
        p2.s.close()
        assertEquals("speaker:false", next())
        assertEquals("status:RECONNECTING", next())
        assertEquals(mapOf("key" to "N".repeat(22)), accept().hello())
    }
}
