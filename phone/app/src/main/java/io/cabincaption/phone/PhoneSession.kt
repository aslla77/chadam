package io.cabincaption.phone

import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.Ctrl
import io.cabincaption.protocol.Frame
import io.cabincaption.protocol.FrameCodec
import io.cabincaption.protocol.FrameType
import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 차 앱 한 대와의 TLS 1.3 연결(ADR-0004·0005). 처음·재접속 모두 QR 지문([JoinUri.pin])과 차 인증서가 맞아야 붙는다.
 * QR 토큰으로 처음 붙고, 끊기면 받은 재접속 키로 다시 붙는다(키는 붙을 때마다 차가 새로 준다). 나가기는 bye를 보내 차가 키를 바로 버리게 한다.
 * 환영 뒤 [pingMs]마다 ping을 보내고, [peerTimeoutMs] 동안 차에서 아무것도 못 받으면 끊긴 것으로 보고 다시 붙는다.
 * 키·연결은 메모리에만 둔다. 리스너는 연결 스레드에서 불린다.
 */
class PhoneSession(
    private val join: JoinUri,
    private val listener: Listener,
    private val connectTimeoutMs: Int = 5_000,
    private val welcomeTimeoutMs: Int = 5_000,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val pingMs: Long = Wire.PING_MS,
    private val peerTimeoutMs: Int = Wire.PEER_TIMEOUT_MS,
    private val name: String? = null,
    private val byeGraceMs: Long = BYE_GRACE_MS,
) {
    enum class Status { CONNECTING, CONNECTED, RECONNECTING }

    enum class End {
        /** 새 참가를 차가 거절(사유 있음). */
        REJECTED,

        /** 처음 연결부터 닿지 않음. */
        UNREACHABLE,

        /** 차가 꺼짐(off 수신). */
        CAR_OFF,

        /** 연결이 끊긴 뒤 다시 붙지 못함(차가 꺼졌거나 키가 무효). */
        LOST,

        /** 차 화면에서 이 폰을 내보냄(kicked 수신). 다시 붙지 않는다. */
        KICKED,
    }

    interface Listener {
        fun onStatus(status: Status)

        fun onEnd(end: End, reason: RejectReason?)

        /** [reason]: 기다림·실패 이유(Wire.SAID_NAV·SAID_CALL·그 밖의 값). 없으면 null. */
        fun onSaid(id: String, state: SayState, reason: String?)

        fun onSpeaker(on: Boolean)

        /** 차 마이크 청크(연결 스레드, 100ms마다). [rxMs]는 받은 폰 시각. */
        fun onCarPcm(chunk: CarChunk, rxMs: Long)

        /** 누군가 전체 자막을 멈춤/재개(이 폰 포함). */
        fun onPause(on: Boolean)

        /** 차 소리 입력 상태: [silenced] = 다른 앱이 마이크를 가져감, [input] = 거짓이면 차 쪽 입력이 없음(자막이 오지 않음). */
        fun onCarMic(silenced: Boolean, input: Boolean)

        /** 차에서 내비 안내가 나오는 중(그동안 차 마이크 소리가 오지 않는다). 환영 직후에도 온다. */
        fun onNav(on: Boolean)

        /** 차·폰 시계 차이(차 − 폰, ms)와 그때 왕복 시간. */
        fun onClock(offsetMs: Long, rttMs: Long)

        /** 다른 폰이 보낸 글을 차가 읽기 시작함(단톡방식). [id]는 차가 매긴 번호. 형식이 맞는 것만 온다. */
        fun onShared(id: String, text: String, name: String?)
    }

    private sealed interface Outcome {
        data object Off : Outcome

        data object Kicked : Outcome

        data class Rejected(val reason: RejectReason?) : Outcome

        data object Dropped : Outcome
    }

    @Volatile
    private var left = false

    /** TLS 아래 소켓. 이것을 닫으면 막힌 읽기·쓰기가 TLS 잠금을 기다리지 않고 끝난다. */
    @Volatile
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var key: String? = null
    private var worker: Thread? = null
    private var clock = ClockSync()
    private var pingSentMs = 0L

    fun start() {
        check(worker == null) { "already started" }
        worker = thread(name = "phone-session", isDaemon = true) { run() }
    }

    /** 이 폰만 빠진다. 차에 bye를 보내고 차가 닫기를 [byeGraceMs]까지 기다린 뒤 닫는다. 이후 리스너는 불리지 않는다. 메인 스레드에서 불러도 된다. */
    fun leave() {
        left = true
        val w = worker
        w?.interrupt()
        val s = socket
        thread(name = "phone-bye", isDaemon = true) { send(Wire.bye()) }
        thread(name = "phone-leave", isDaemon = true) {
            try {
                w?.join(byeGraceMs)
            } catch (_: InterruptedException) {
            }
            closeQuietly(s)
        }
    }

    /** 글자 음성(PCM16 mono)을 차로 보낸다. [text]를 함께 보내면 차가 읽을 때 다른 폰에도 보인다. 연결이 없거나 쓰다 끊기면 false. */
    fun say(id: String, rate: Int, pcm: ByteArray, text: String? = null): Boolean = synchronized(writeLock) {
        val o = out ?: return false
        try {
            FrameCodec.write(o, Wire.say(id, rate, text))
            var off = 0
            while (off < pcm.size) {
                val n = minOf(Wire.PCM_CHUNK, pcm.size - off)
                FrameCodec.write(o, Wire.pcm(pcm.copyOfRange(off, off + n)))
                off += n
            }
            FrameCodec.write(o, Wire.sayEnd(id))
            true
        } catch (e: IOException) {
            false
        }
    }

    /** 차 스피커를 거치지 않고 다른 폰에만 보일 글을 보낸다(결과는 onSaid). 연결이 없거나 쓰다 끊기면 false. */
    fun note(id: String, text: String): Boolean = send(Wire.note(id, text))

    /** 전체 자막 멈춤/재개를 차에 요청한다. 차가 모든 폰에 결과를 알린다. 연결이 없으면 false. */
    fun setPause(on: Boolean): Boolean = send(Wire.pause(on))

    private fun send(f: Frame): Boolean = synchronized(writeLock) {
        val o = out ?: return false
        try {
            FrameCodec.write(o, f)
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun ping() {
        pingSentMs = System.currentTimeMillis()
        send(Wire.clockPing())
    }

    private fun run() {
        emitStatus(Status.CONNECTING)
        var attempt = 0
        while (!left) {
            val o = once { attempt = 0 }
            if (left) return
            when (o) {
                Outcome.Off -> return end(End.CAR_OFF, null)
                Outcome.Kicked -> return end(End.KICKED, null)
                is Outcome.Rejected -> return end(if (key == null) End.REJECTED else End.LOST, o.reason)
                Outcome.Dropped -> {
                    if (key == null) return end(End.UNREACHABLE, null)
                    if (attempt >= retryDelaysMs.size) return end(End.LOST, null)
                    emitStatus(Status.RECONNECTING)
                    try {
                        Thread.sleep(retryDelaysMs[attempt++])
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    private fun once(onWelcome: () -> Unit): Outcome {
        val s = Socket()
        socket = s
        if (left) closeQuietly(s)
        var welcomed = false
        var pinger: Thread? = null
        try {
            s.connect(InetSocketAddress(join.host, join.port), connectTimeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = welcomeTimeoutMs
            val tls = PinnedTls.wrap(s, join.host, join.port, join.pin)
            val input = BufferedInputStream(tls.inputStream)
            val o = tls.outputStream
            val k = key
            val who = if (k != null) mapOf(Wire.KEY to k) else mapOf(Wire.TOKEN to join.token)
            FrameCodec.write(o, Wire.hello(if (name != null) who + (Wire.NAME to name) else who))
            while (true) {
                val f = FrameCodec.read(input) ?: return Outcome.Dropped
                if (f.type == FrameType.CAR_PCM) {
                    val c = CarChunk.parse(f.body)
                    if (welcomed && c != null && !left) listener.onCarPcm(c, System.currentTimeMillis())
                    continue
                }
                if (f.type != FrameType.CTRL) continue
                val m = Ctrl.decode(f.body)
                when (m[Wire.TYPE]) {
                    Wire.WELCOME -> {
                        val newKey = m[Wire.KEY] ?: return Outcome.Dropped
                        key = newKey
                        s.soTimeout = peerTimeoutMs
                        synchronized(writeLock) { out = o }
                        welcomed = true
                        onWelcome()
                        emitStatus(Status.CONNECTED)
                        clock = ClockSync()
                        ping()
                        pinger = startPinger()
                    }
                    Wire.REJECT -> return Outcome.Rejected(RejectReason.of(m[Wire.REASON]))
                    Wire.OFF -> return Outcome.Off
                    Wire.KICKED -> if (welcomed) return Outcome.Kicked
                    Wire.SAID -> {
                        val id = m[Wire.ID]
                        val st = SayState.of(m[Wire.STATE])
                        if (welcomed && id != null && st != null && !left) listener.onSaid(id, st, m[Wire.REASON])
                    }
                    Wire.NAV -> {
                        val on = Wire.parseFlag(m[Wire.ON])
                        if (welcomed && on != null && !left) listener.onNav(on)
                    }
                    Wire.SHARED -> {
                        val id = m[Wire.ID]
                        val text = m[Wire.TEXT]
                        if (welcomed && Wire.validId(id) && Wire.validText(text) && !left) listener.onShared(id!!, text!!, m[Wire.NAME]?.takeIf(Wire::validName))
                    }
                    Wire.SPEAKER -> if (welcomed && !left) listener.onSpeaker(m[Wire.ON] == "1")
                    Wire.PAUSE -> {
                        val on = Wire.parseFlag(m[Wire.ON])
                        if (welcomed && on != null && !left) listener.onPause(on)
                    }
                    Wire.MIC -> {
                        val silenced = Wire.parseFlag(m[Wire.SILENCED])
                        val input = Wire.parseFlag(m[Wire.INPUT])
                        if (welcomed && silenced != null && input != null && !left) listener.onCarMic(silenced, input)
                    }
                    Wire.CLOCK -> {
                        val now = m[Wire.NOW]?.toLongOrNull()
                        if (welcomed && now != null && !clock.done) {
                            clock.sample(pingSentMs, now, System.currentTimeMillis())
                            if (clock.done) {
                                val off = clock.offsetMs
                                val rtt = clock.rttMs
                                if (off != null && rtt != null && !left) listener.onClock(off, rtt)
                            } else {
                                ping()
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return Outcome.Dropped
        } finally {
            pinger?.interrupt()
            synchronized(writeLock) { out = null }
            closeQuietly(s)
            if (welcomed && !left) listener.onSpeaker(false)
        }
    }

    /** 이 연결이 끝나면(쓰기 실패·interrupt) 멈춘다. */
    private fun startPinger(): Thread = thread(name = "phone-ping", isDaemon = true) {
        try {
            while (true) {
                Thread.sleep(pingMs)
                if (!send(Wire.ping())) return@thread
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun emitStatus(s: Status) {
        if (!left) listener.onStatus(s)
    }

    private fun end(e: End, reason: RejectReason?) {
        key = null
        if (!left) listener.onEnd(e, reason)
    }

    private companion object {
        val RETRY_DELAYS_MS = listOf(500L, 1_000L, 1_000L, 2_000L, 2_000L, 2_000L, 2_000L, 2_000L)

        /** bye 뒤 차가 연결을 닫기를 기다리는 시간. 그 뒤엔 우리가 닫는다. */
        const val BYE_GRACE_MS = 500L

        fun closeQuietly(s: Socket?) {
            try {
                s?.close()
            } catch (_: IOException) {
            }
        }
    }
}
