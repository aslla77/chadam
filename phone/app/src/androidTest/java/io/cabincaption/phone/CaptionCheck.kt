package io.cabincaption.phone

import android.app.Instrumentation
import android.os.Bundle
import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.SayState
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 자막 경로 기기 점검(B-9~B-12). 폰 화면 없이 PhoneSession + CaptionEngine + CaptionLog를 앱과 같은 방식으로 묶는다.
 * 순서: 연결 → [listen_s]초 자막 → 전체 멈춤 [pause_s]초(받은 청크 0 확인) → 재개 → 시험 문장 1개를 차 스피커로(재생 중 받은 청크 0 확인)
 * → 3초 → 끝([end]=leave: 나가기, off: 차가 꺼질 때까지 대기) → 기록 지움 확인.
 * 로그 태그 CabinE2e. 시연 음원(합성 음성)만 쓴다는 전제로 자막 글자를 로그에 남긴다.
 */
class CaptionCheck(
    private val inst: Instrumentation,
    private val args: Bundle,
    private val out: (String) -> Unit,
) {
    private val ctx = inst.targetContext
    private val events = LinkedBlockingQueue<String>()
    private val captions = CaptionLog()
    private val sayLog = SayLog()
    private val rx = AtomicLong()
    private val gaps = AtomicLong()

    @Volatile
    private var engine: CaptionEngine? = null

    @Volatile
    private var lastSeq = -1L

    @Volatile
    private var quiet = 3

    @Volatile
    private var paused = false

    @Volatile
    private var rxAtSpeakerOn = -1L
    private var speakerRx = 0L
    private val t0 = System.currentTimeMillis()

    private fun t() = System.currentTimeMillis() - t0

    private fun onMain(block: () -> Unit) = inst.runOnMainSync(block)

    private val sessionEvents = object : PhoneSession.Listener {
        override fun onStatus(status: PhoneSession.Status) {
            events += "status:$status"
        }

        override fun onEnd(end: PhoneSession.End, reason: RejectReason?) {
            events += "end:$end:${reason?.wire}"
        }

        override fun onSaid(id: String, state: SayState, reason: String?) {
            sayLog.car(id, state, reason)
            events += "said:$id:${state.wire}"
        }

        override fun onSpeaker(on: Boolean) {
            if (on) {
                rxAtSpeakerOn = rx.get()
                inst.runOnMainSync {
                    captions.interrupt(null)
                    engine?.interrupt()
                }
            } else if (rxAtSpeakerOn >= 0) {
                speakerRx += rx.get() - rxAtSpeakerOn
                out("[check] t=${t()} speaker on→off rxDuring=${rx.get() - rxAtSpeakerOn}")
                rxAtSpeakerOn = -1
            }
            out("[speaker] t=${t()} on=$on rx=${rx.get()}")
            events += "speaker:$on"
        }

        override fun onCarPcm(chunk: CarChunk, rxMs: Long) {
            val n = rx.incrementAndGet()
            val prev = lastSeq
            if (prev >= 0 && chunk.seq != prev + 1) {
                gaps.incrementAndGet()
                out("[gap] t=${t()} after=$prev next=${chunk.seq} missing=${chunk.seq - prev - 1}")
            }
            lastSeq = chunk.seq
            val loud = CaptionLog.rms(chunk.pcm) >= CaptionLog.ONSET_RMS
            if (loud && quiet >= CaptionLog.QUIET_CHUNKS) out("[onset] seq=${chunk.seq} capMs=${chunk.captureMs} rxMs=$rxMs")
            quiet = if (loud) 0 else quiet + 1
            if (n % 10 == 1L) out("[rx] n=$n seq=${chunk.seq} capMs=${chunk.captureMs} rxMs=$rxMs")
            captions.chunk(chunk.seq, chunk.captureMs, rxMs, chunk.pcm)
            engine?.feed(chunk.pcm)
        }

        override fun onPause(on: Boolean) {
            out("[pause] t=${t()} on=$on rx=${rx.get()}")
            if (paused != on) inst.runOnMainSync {
                if (on) captions.interrupt(CaptionLog.NoticeKind.PAUSED) else captions.notice(CaptionLog.NoticeKind.RESUMED)
                if (on) engine?.interrupt()
            }
            paused = on
            events += "pause:$on"
        }

        override fun onCarMic(silenced: Boolean, input: Boolean) {
            out("[carmic] t=${t()} silenced=$silenced input=$input")
        }

        override fun onNav(on: Boolean) = out("[nav] t=${t()} on=$on")

        override fun onShared(id: String, text: String, name: String?) = out("[shared] t=${t()} id=$id chars=${text.length}")

        override fun onClock(offsetMs: Long, rttMs: Long) {
            captions.clockOffsetMs = offsetMs
            out("[clock] offsetMs=$offsetMs rttMs=$rttMs")
        }
    }

    private val engineEvents = object : CaptionEngine.Listener {
        override fun onPartial(text: String, final: Boolean) {
            val now = System.currentTimeMillis()
            val no = captions.partial(text, final, now)
            if (no != null) {
                val line = captions.snapshot().filterIsInstance<CaptionLog.Entry.Line>().last()
                out("[first] line=$no phoneMs=$now latencyMs=${line.latencyMs} seq=$lastSeq")
            }
            out("[cap] phoneMs=$now final=$final chars=${text.trim().length}")
        }

        override fun onEndOfSpeech() {
            captions.endOfSpeech()
            out("[eos] phoneMs=${System.currentTimeMillis()}")
        }

        override fun onOnDevice(active: Boolean) = out("[where] onDevice=$active")

        override fun onProblem(notice: RecoveryPolicy.Notice?) = out("[problem] $notice")

        override fun onPhoneMic() = out("[mic] PHONE MIC suspected")
    }

    private fun await(prefix: String, seconds: Long): String? {
        val end = System.currentTimeMillis() + seconds * 1_000
        while (System.currentTimeMillis() < end) {
            val e = events.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (e.startsWith(prefix)) return e
            if (e.startsWith("end:")) {
                events += e
                return null
            }
        }
        return null
    }

    fun run(): Boolean {
        val join = JoinUri.parse(args.getString("uri")) ?: return false.also { out("bad uri") }
        val listenS = args.getString("listen_s")?.toLongOrNull() ?: 70
        val pauseS = args.getString("pause_s")?.toLongOrNull() ?: 5
        val endMode = args.getString("end") ?: "leave"
        val session = PhoneSession(join, sessionEvents)
        val tts = PhoneTts(ctx)
        session.start()
        try {
            await("status:CONNECTED", 10) ?: return false.also { out("no welcome") }
            out("[step] t=${t()} connected")
            onMain { engine = CaptionEngine(ctx, engineEvents) { out("[engine] $it") }.also { it.start() } }
            Thread.sleep(listenS * 1_000)

            out("[step] t=${t()} pause test")
            session.setPause(true)
            await("pause:true", 5) ?: return false.also { out("pause not echoed") }
            val rxPause = rx.get()
            Thread.sleep(pauseS * 1_000)
            val pausedRx = rx.get() - rxPause
            out("[check] pause ${pauseS}s rxDuring=$pausedRx")
            session.setPause(false)
            await("pause:false", 5) ?: return false.also { out("resume not echoed") }
            Thread.sleep(3_000)

            out("[step] t=${t()} speaker test")
            val text = "에어컨 좀 약하게 해 주세요."
            sayLog.add("c1", text)
            captions.mine("c1")
            val pcm = tts.synthesize(text, "c1") ?: return false.also { out("synth failed") }
            out("[say] sent=${session.say("c1", pcm.rate, pcm.data)} bytes=${pcm.data.size}")
            await("said:c1:done", 30) ?: out("said done not seen")
            await("speaker:false", 5)
            Thread.sleep(3_000)

            onMain { engine?.let { e -> out("[engine-sum] sessions=${e.sessions} written=${e.written} bufferDropped=${e.bufferDropped} phoneMicEvents=${e.phoneMicEvents} maxRecordings=${e.maxRecordings}") } }
            out("[sum] rx=${rx.get()} gaps=${gaps.get()} pausedRx=$pausedRx speakerRx=$speakerRx")
            out("[latency] total=${captions.totalStats()} recognizer=${captions.recognizerStats()}")
            captions.snapshot().forEach { e ->
                when (e) {
                    is CaptionLog.Entry.Line -> out("[line] no=${e.no} final=${e.final} latencyMs=${e.latencyMs} chars=${e.text.length}")
                    is CaptionLog.Entry.Shared -> out("[entry] shared no=${e.no} id=${e.id} chars=${e.text.length}")
                    else -> out("[entry] $e")
                }
            }

            if (endMode == "off") {
                out("[step] t=${t()} waiting for car off")
                val e = await("end:CAR_OFF", 180)
                out("[end] ${e ?: "timeout"}")
            } else {
                out("[step] t=${t()} leave")
                session.leave()
            }
            // 앱(MainActivity.endSession)과 같은 정리
            onMain {
                engine?.stop()
                engine = null
                captions.clear()
                sayLog.clear()
            }
            out("[cleared] captions=${captions.size()} says=${sayLog.snapshot().size}")
            return pausedRx == 0L && speakerRx == 0L
        } finally {
            session.leave()
            tts.shutdown()
            onMain { engine?.stop() }
        }
    }
}
