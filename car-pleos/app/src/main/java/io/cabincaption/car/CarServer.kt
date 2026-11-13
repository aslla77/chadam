package io.cabincaption.car

import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.Ctrl
import io.cabincaption.protocol.Frame
import io.cabincaption.protocol.FrameCodec
import io.cabincaption.protocol.FrameType
import io.cabincaption.protocol.ProtocolException
import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

interface SessionServer {
    /** 포트를 열고 실제로 열린 포트를 돌려준다. 실패하거나 이미 끈 서버면 IOException. */
    fun start(port: Int): Int

    /** 모든 연결에 OFF를 보내 보고(짧게 기다림) 무조건 닫은 뒤 토큰·키·연결 기록을 지운다. 멈춘 폰이 있어도 곧 돌아온다. */
    fun stop()

    fun phones(): Int

    fun token(now: Long): String

    /** 차 스피커가 폰 소리를 재생 중(알림음 포함). 참이면 차 마이크 업로드를 멈춘다. */
    fun speakerBusy(): Boolean

    /** 차 마이크 100ms 청크. 업로드 조건(FR-9)이 맞으면 모든 폰에 보내고, 아니면 버린다. 저장하지 않는다. */
    fun carPcm(pcm: ByteArray, captureMs: Long)

    /** 다른 앱이 마이크를 가져가 우리 녹음이 무음 처리됨(P2b, AudioRecordingCallback). */
    fun setMicSilenced(on: Boolean)

    /** 차 쪽 소리 입력이 돌고 있는지(권한 없음·시작 실패·입력 중단이면 false). 폰에 알린다. */
    fun setMicInput(on: Boolean)

    /** 내비 안내 재생 중(차 마이크 업로드를 멈춤, R-28). 폰에 알린다. */
    fun setNav(on: Boolean)

    /** 폰 누군가가 전체 자막을 멈춤(FR-6). */
    fun paused(): Boolean

    fun nav(): Boolean

    fun micSilenced(): Boolean
}

/**
 * 차 앱의 TCP 서버(ADR-0004). 켜져 있는 동안만 포트를 연다. 저장 없음. 폰이 보낸 소리는 [speaker] 큐로 재생한다.
 * 차 마이크 청크는 [UploadGate] 조건이 맞을 때만 모든 폰에 보낸다.
 * 보내기는 폰마다 [SendQueue] + 전용 스레드라서 느린 폰 하나가 캡처·다른 폰·재생 스레드를 막지 않는다(ADR-0004 결정 5).
 * 살아 있음: [pingMs]마다 ping을 보내고, [peerTimeoutMs] 동안 아무것도 못 받거나 쓰기가 [writeTimeoutMs] 넘게 막히면 끊는다.
 * 단톡방식 공유: 폰이 보낸 글은 차 스피커에서 소리가 처음 난 뒤(onAudible) 보낸 폰을 뺀 모든 폰에 한 번 보낸다. 글 내용은 로그에 남기지 않는다.
 * 소리 없이 보내기(note): [speaker]·업로드 조건을 거치지 않고 받자마자 다른 폰에 보낸다.
 */
class CarServer(
    private val lobby: Lobby,
    private val isDriving: () -> Boolean,
    private val speaker: SpeakerQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val helloTimeoutMs: Int = 5_000,
    private val maxSockets: Int = Lobby.MAX_PHONES + 4,
    private val pingMs: Long = Wire.PING_MS,
    private val peerTimeoutMs: Int = Wire.PEER_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val offGraceMs: Long = OFF_GRACE_MS,
    private val audioBacklog: Int = SendQueue.MAX_AUDIO,
) : SessionServer {

    /** [member] = 재접속해도 같은 폰이면 같은 번호([Admit.Accepted.member]). */
    private inner class Conn(val id: Long, private val socket: Socket, val member: Long, val name: String?) {
        private val out = socket.getOutputStream()
        private val queue = SendQueue(audioBacklog)
        private val writer = thread(start = false, name = "car-send", isDaemon = true) { writeLoop() }

        @Volatile
        private var writingSince = NOT_WRITING

        val droppedAudio: Long get() = queue.droppedAudio

        fun begin() = writer.start()

        /** 제어 프레임. 이 폰 앞에 제어 프레임이 너무 쌓였으면(받지 못함) 연결을 닫고 false. */
        fun send(f: Frame): Boolean = when (queue.offerControl(f)) {
            SendQueue.Offer.ACCEPTED -> true
            SendQueue.Offer.CLOSED -> false
            SendQueue.Offer.FULL -> {
                log("conn=$id control backlog full, closing")
                close()
                false
            }
        }

        fun sendAudio(f: Frame) = queue.offerAudio(f)

        private fun writeLoop() {
            try {
                while (true) {
                    val f = queue.take() ?: break
                    writingSince = monoMs()
                    FrameCodec.write(out, f)
                    writingSince = NOT_WRITING
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally {
                closeQuietly(socket)
            }
        }

        fun stalled(now: Long): Boolean {
            val since = writingSince
            return since != NOT_WRITING && now - since > writeTimeoutMs
        }

        /** 남은 오디오를 버리고 [last]까지 보낸 뒤 보내기 스레드가 소켓을 닫는다. */
        fun finish(last: Frame) = queue.finish(last)

        fun awaitWriter(ms: Long) = writer.join(maxOf(1L, ms))

        fun writerDone(): Boolean = !writer.isAlive

        fun close() {
            queue.close()
            closeQuietly(socket)
        }
    }

    private var serverSocket: ServerSocket? = null
    private var timer: ScheduledExecutorService? = null
    private var stopped = false
    private val conns = ConcurrentHashMap<Long, Conn>()
    private val nextId = AtomicLong()
    private val nextShareId = AtomicLong()
    private val open = AtomicInteger()
    private val gate = UploadGate()

    @Volatile
    private var paused = false

    @Volatile
    private var silenced = false

    @Volatile
    private var micInput = false

    @Volatile
    private var nav = false

    @Volatile
    private var uploadState: String? = null

    init {
        speaker.onBusy = { on ->
            val f = Wire.speaker(on)
            conns.values.forEach { it.send(f) }
            log("speaker on=$on phones=${conns.size}")
        }
    }

    @Synchronized
    override fun start(port: Int): Int {
        if (stopped) throw IOException("server stopped")
        check(serverSocket == null) { "server already used" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            closeQuietly(ss)
            throw e
        }
        serverSocket = ss
        val t = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "car-timer").apply { isDaemon = true } }
        t.scheduleWithFixedDelay({ heartbeat() }, pingMs, pingMs, TimeUnit.MILLISECONDS)
        val watchMs = maxOf(10L, minOf(WATCH_MS, writeTimeoutMs / 2))
        t.scheduleWithFixedDelay({ watchWrites() }, watchMs, watchMs, TimeUnit.MILLISECONDS)
        timer = t
        thread(name = "car-accept", isDaemon = true) { acceptLoop(ss) }
        log("listening port=${ss.localPort}")
        return ss.localPort
    }

    private fun heartbeat() {
        val f = Wire.ping()
        conns.values.forEach { it.send(f) }
    }

    /** 쓰기가 오래 막힌 연결(받지 않는 폰)을 닫는다. 닫으면 그 폰의 읽기 스레드가 끝나며 정원·업로드 대상에서 빠진다. */
    private fun watchWrites() {
        val now = monoMs()
        conns.values.forEach {
            if (it.stalled(now)) {
                log("conn=${it.id} write blocked > ${writeTimeoutMs}ms, closing")
                it.close()
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            if (open.incrementAndGet() > maxSockets) {
                open.decrementAndGet()
                closeQuietly(s)
                log("refused: too many sockets")
                continue
            }
            thread(name = "car-conn", isDaemon = true) { serve(s) }
        }
    }

    private fun serve(s: Socket) {
        val id = nextId.incrementAndGet()
        var key: String? = null
        var conn: Conn? = null
        // HELLO 전체 마감(M-1): soTimeout은 read() 한 번에만 걸리므로, 조금씩 보내며 버티는 연결은 이 예약이 닫는다
        val deadline = try {
            synchronized(this) { timer }?.schedule({ closeQuietly(s) }, helloTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
        try {
            if (deadline == null) return
            s.tcpNoDelay = true
            // 커널 송신 버퍼도 약 1초 분량으로 둔다: 큰 버퍼는 느린 폰에 몇 초 묵은 소리를 쌓는다(ADR-0004 결정 5의 1초)
            s.sendBufferSize = SEND_BUFFER_BYTES
            s.soTimeout = helloTimeoutMs
            val input = BufferedInputStream(s.getInputStream())
            val hello = FrameCodec.read(input, Wire.HELLO_MAX_BODY) ?: return
            if (!deadline.cancel(false)) return
            if (hello.type != FrameType.HELLO) {
                log("conn=$id closed: first frame ${hello.type}")
                return
            }
            val fields = try {
                Ctrl.decode(hello.body)
            } catch (e: ProtocolException) {
                FrameCodec.write(s.getOutputStream(), reject(RejectReason.INVALID))
                return
            }
            val admit = if (isStopped()) Admit.Rejected(RejectReason.INVALID) else lobby.admit(fields, id, isDriving(), clock())
            when (admit) {
                is Admit.Rejected -> {
                    log("conn=$id rejected ${admit.reason.wire}")
                    FrameCodec.write(s.getOutputStream(), reject(admit.reason))
                }
                is Admit.Accepted -> {
                    key = admit.key
                    val c = Conn(id, s, admit.member, fields[Wire.NAME]?.takeIf(Wire::validName))
                    conn = c
                    // 줄에 먼저 넣고 등록한다: 환영보다 먼저 오디오가 나가지 않는다
                    c.send(Wire.ctrl(Wire.TYPE to Wire.WELCOME, Wire.KEY to admit.key))
                    c.send(Wire.mic(silenced, micInput))
                    c.send(Wire.pause(paused))
                    c.send(Wire.nav(nav))
                    if (speaker.busy) c.send(Wire.speaker(true))
                    if (!register(c)) return
                    c.begin()
                    admit.replacedConn?.let { conns.remove(it)?.close() }
                    log("conn=$id accepted phones=${lobby.phones()} rejoin=${admit.rejoin}")
                    s.soTimeout = peerTimeoutMs
                    session(c, input)
                }
            }
        } catch (e: IOException) {
            log("conn=$id closed: ${e.javaClass.simpleName}")
        } finally {
            deadline?.cancel(false)
            conn?.let { c ->
                conns.remove(id, c)
                c.close()
                if (c.droppedAudio > 0) log("conn=$id audio dropped=${c.droppedAudio} (slow phone)")
            }
            key?.let { lobby.left(it, id, clock()) }
            closeQuietly(s)
            open.decrementAndGet()
        }
    }

    private fun session(conn: Conn, input: BufferedInputStream) {
        val asm = UtteranceAssembler()
        while (true) {
            val f = FrameCodec.read(input) ?: return
            val result = when (f.type) {
                FrameType.PHONE_PCM -> asm.pcm(f.body)
                FrameType.CTRL -> {
                    val m = Ctrl.decode(f.body)
                    when (m[Wire.TYPE]) {
                        Wire.SAY -> asm.start(m)
                        Wire.SAY_END -> asm.end(m)
                        Wire.PAUSE -> {
                            Wire.parseFlag(m[Wire.ON])?.let { setPaused(it, conn.id) }
                            UtteranceAssembler.Result.Ignored
                        }
                        Wire.NOTE -> {
                            note(conn, m)
                            UtteranceAssembler.Result.Ignored
                        }
                        Wire.CLOCK -> {
                            conn.send(Wire.clockPong(clock()))
                            UtteranceAssembler.Result.Ignored
                        }
                        else -> UtteranceAssembler.Result.Ignored
                    }
                }
                else -> UtteranceAssembler.Result.Ignored
            }
            when (result) {
                is UtteranceAssembler.Result.Ready -> {
                    val u = result.utterance
                    val text = u.text
                    log("conn=${conn.id} say id=${u.id} received rate=${u.rate} frames=${u.pcm.size / 2} chars=${text?.length ?: 0}")
                    val shared = AtomicBoolean(false)
                    speaker.enqueue(
                        u,
                        report = { state, reason ->
                            if (!conn.send(Wire.said(u.id, state, reason))) log("conn=${conn.id} said ${state.wire} not sent")
                        },
                        // 내비 때문에 끊겨 다시 읽어도 한 번만 보인다
                        onAudible = { if (text != null && shared.compareAndSet(false, true)) share(conn, u.id, text) },
                    )
                }
                is UtteranceAssembler.Result.Failed -> {
                    log("conn=${conn.id} say id=${result.id} dropped: ${result.reason}")
                    conn.send(Wire.said(result.id, SayState.FAILED, result.reason))
                }
                UtteranceAssembler.Result.Pending, UtteranceAssembler.Result.Ignored -> Unit
            }
        }
    }

    private fun share(from: Conn, sayId: String, text: String) {
        log("conn=${from.id} say id=$sayId shared to=${sendToOthers(from, text)} chars=${text.length}")
    }

    private fun note(from: Conn, fields: Map<String, String>) {
        val id = fields[Wire.ID]?.takeIf(Wire::validId) ?: return log("conn=${from.id} note ignored: bad id")
        val text = fields[Wire.TEXT]
        if (text == null || !Wire.validText(text)) {
            log("conn=${from.id} note id=$id dropped: ${UtteranceAssembler.REASON_BAD_FORMAT} chars=${text?.length ?: 0}")
            from.send(Wire.said(id, SayState.FAILED, UtteranceAssembler.REASON_BAD_FORMAT))
            return
        }
        val to = sendToOthers(from, text)
        log("conn=${from.id} note id=$id shared to=$to chars=${text.length}")
        from.send(if (to > 0) Wire.said(id, SayState.DONE) else Wire.said(id, SayState.FAILED, Wire.SAID_NOBODY))
    }

    /** 보낸 폰(재접속한 연결 포함)을 뺀 지금 연결된 모든 폰에 보내고, 받은 폰 수를 돌려준다. 받을 폰이 없으면 번호를 쓰지 않는다. */
    private fun sendToOthers(from: Conn, text: String): Int {
        val to = connections().filter { it.member != from.member }
        if (to.isEmpty()) return 0
        val f = Wire.shared(nextShareId.incrementAndGet().toString(), text, from.name)
        return to.count { it.send(f) }
    }

    private fun setPaused(on: Boolean, by: Long) {
        synchronized(this) {
            if (stopped || paused == on) return
            paused = on
        }
        val f = Wire.pause(on)
        conns.values.forEach { it.send(f) }
        log("pause on=$on by conn=$by phones=${conns.size} ${gate.counters()}")
    }

    override fun setMicSilenced(on: Boolean) {
        synchronized(this) {
            if (stopped || silenced == on) return
            silenced = on
        }
        broadcastMic()
        log("mic silenced=$on phones=${conns.size}")
    }

    override fun setMicInput(on: Boolean) {
        synchronized(this) {
            if (stopped || micInput == on) return
            micInput = on
        }
        broadcastMic()
        log("mic input=$on phones=${conns.size}")
    }

    override fun setNav(on: Boolean) {
        synchronized(this) {
            if (stopped || nav == on) return
            nav = on
        }
        val f = Wire.nav(on)
        conns.values.forEach { it.send(f) }
        log("nav on=$on phones=${conns.size} ${gate.counters()}")
    }

    private fun broadcastMic() {
        val f = Wire.mic(silenced, micInput)
        conns.values.forEach { it.send(f) }
    }

    /** 캡처 스레드가 부른다. 폰마다 줄에 넣기만 하므로 막히지 않는다(느린 폰은 그 폰 줄에서 오래된 청크부터 버린다). */
    override fun carPcm(pcm: ByteArray, captureMs: Long) {
        if (isStopped()) return
        val targets = connections()
        val d = gate.offer(targets.size, paused, speaker.busy, nav, silenced)
        val state = d.drop?.name ?: "SEND"
        if (state != uploadState) {
            uploadState = state
            log("upload $state seq=${d.seq} phones=${targets.size} ${gate.counters()}")
        }
        if (!d.send) return
        val f = CarChunk(d.seq, captureMs, pcm).frame()
        targets.forEach { it.sendAudio(f) }
    }

    @Synchronized
    private fun isStopped() = stopped

    // Kotlin toList()는 크기 1이면 size를 본 뒤 next()를 불러, 그사이 연결이 빠지면 NoSuchElementException을 던진다
    private fun connections(): List<Conn> = ArrayList(conns.values)

    @Synchronized
    private fun register(conn: Conn): Boolean {
        if (stopped) return false
        conns[conn.id] = conn
        return true
    }

    override fun stop() {
        val snapshot: List<Conn>
        val t: ScheduledExecutorService?
        synchronized(this) {
            if (stopped) return
            stopped = true
            snapshot = connections()
            conns.clear()
            serverSocket?.let { closeQuietly(it) }
            t = timer
        }
        t?.shutdown() // 주기 작업(ping·감시)은 멈추고, 남은 HELLO 마감은 그대로 실행된다
        speaker.shutdown()
        val off = Wire.ctrl(Wire.TYPE to Wire.OFF)
        snapshot.forEach { it.finish(off) }
        val until = monoMs() + offGraceMs
        snapshot.forEach { it.awaitWriter(until - monoMs()) }
        val unsent = snapshot.count { !it.writerDone() }
        snapshot.forEach { it.close() }
        lobby.clear()
        log("upload final ${gate.counters()}")
        log("stopped, closed ${snapshot.size} connections (off not confirmed=$unsent)")
    }

    override fun phones(): Int = lobby.phones()

    override fun token(now: Long): String = lobby.token(now)

    override fun speakerBusy(): Boolean = speaker.busy

    override fun paused(): Boolean = paused

    override fun micSilenced(): Boolean = silenced

    override fun nav(): Boolean = nav

    fun uploadCounters(): UploadGate.Counters = gate.counters()

    private fun reject(reason: RejectReason) = Wire.ctrl(Wire.TYPE to Wire.REJECT, Wire.REASON to reason.wire)

    companion object {
        /** 이만큼 쓰기가 막히면(폰이 받지 않음) 그 연결을 닫는다. */
        const val WRITE_TIMEOUT_MS = 5_000L

        /** 끄기 때 off를 보내 보는 시간. 그 뒤엔 보냈든 못 보냈든 닫는다. */
        const val OFF_GRACE_MS = 500L

        /** 32KB ≈ 차 마이크 1초(16kHz × 2B). */
        const val SEND_BUFFER_BYTES = 32 * 1024

        private const val WATCH_MS = 500L
        private const val NOT_WRITING = Long.MIN_VALUE

        private fun monoMs(): Long = System.nanoTime() / 1_000_000

        private fun closeQuietly(c: java.io.Closeable) {
            try {
                c.close()
            } catch (_: IOException) {
            }
        }
    }
}
