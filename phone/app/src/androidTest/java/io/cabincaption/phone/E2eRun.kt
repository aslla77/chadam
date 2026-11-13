package io.cabincaption.phone

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.SayState
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 기기 점검용(화면 없이, 잠긴 폰에서도). 동의 화면을 건너뛰므로 사람 사용 경로가 아니다. 폰 스피커로는 소리를 내지 않는다(synthesizeToFile만).
 * - 기본(say): QR 링크로 붙어 시험 문장 3개([PHRASES])를 폰 TTS PCM으로 연달아 보내고 차 응답을 기다린다.
 * - `-e mode caption`: 차 소리를 폰 인식기로 자막으로 바꾸고 멈춤·재생 중 수신 0·지연을 기록한다([CaptionCheck]).
 * 실행: adb shell am instrument -w -e uri '<QR 링크>' [-e mode caption] io.cabincaption.phone.test/io.cabincaption.phone.E2eRun
 */
class E2eRun : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val out = Bundle()
        val ok = try {
            if (args.getString("mode") == "caption") CaptionCheck(this, args) { log(out, it) }.run() else run(out)
        } catch (e: Exception) {
            log(out, "error ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        out.putString(REPORT_KEY_STREAMRESULT, out.getString("log"))
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, out)
    }

    private fun log(out: Bundle, line: String) {
        Log.i(TAG, line)
        out.putString("log", (out.getString("log") ?: "") + line + "\n")
    }

    private fun run(out: Bundle): Boolean {
        val join = JoinUri.parse(args.getString("uri")) ?: return false.also { log(out, "bad uri") }
        val events = LinkedBlockingQueue<String>()
        val session = PhoneSession(join, object : PhoneSession.Listener {
            override fun onStatus(status: PhoneSession.Status) {
                events += "status:$status"
            }
            override fun onEnd(end: PhoneSession.End, reason: RejectReason?) {
                events += "end:$end:${reason?.wire}"
            }
            override fun onSaid(id: String, state: SayState, reason: String?) {
                events += "said:$id:${state.wire}${reason?.let { ":$it" } ?: ""}"
            }
            override fun onSpeaker(on: Boolean) {
                events += "speaker:$on"
            }
            override fun onCarPcm(chunk: CarChunk, rxMs: Long) = Unit
            override fun onPause(on: Boolean) = Unit
            override fun onCarMic(silenced: Boolean, input: Boolean) = Unit
            override fun onNav(on: Boolean) = Unit
            override fun onClock(offsetMs: Long, rttMs: Long) = Unit
            override fun onShared(id: String, text: String, name: String?) {
                events += "shared:$id:chars=${text.length}"
            }
        })
        session.start()
        val tts = PhoneTts(targetContext)
        try {
            while (true) {
                val e = events.poll(10, TimeUnit.SECONDS) ?: return false.also { log(out, "no welcome") }
                log(out, e)
                if (e == "status:CONNECTED") break
                if (e.startsWith("end:")) return false
            }
            val ids = PHRASES.mapIndexed { i, text ->
                val id = "e${i + 1}"
                val t0 = System.nanoTime()
                val pcm = tts.synthesize(text, id) ?: return false.also { log(out, "$id synth failed") }
                val sent = session.say(id, pcm.rate, pcm.data, text)
                log(out, "$id chars=${text.length} rate=${pcm.rate} bytes=${pcm.data.size} frames=${pcm.data.size / 2} sent=$sent synthSendMs=${(System.nanoTime() - t0) / 1_000_000}")
                id
            }
            val pending = ids.toMutableSet()
            val deadline = System.currentTimeMillis() + 60_000
            while (pending.isNotEmpty() && System.currentTimeMillis() < deadline) {
                val e = events.poll(1, TimeUnit.SECONDS) ?: continue
                log(out, e)
                val parts = e.split(':')
                if (parts[0] == "said" && (parts[2] == "done" || parts[2] == "failed")) {
                    pending -= parts[1]
                    if (parts[2] == "failed") return false
                }
                if (parts[0] == "end") return false
            }
            log(out, "pending=$pending")
            return pending.isEmpty()
        } finally {
            session.leave()
            tts.shutdown()
        }
    }

    private companion object {
        const val TAG = "CabinE2e"
        val PHRASES = listOf(
            "에어컨 좀 약하게 해 주세요.",
            "다음 휴게소에서 화장실 들러 주세요.",
            "음악 소리 조금만 줄여 주세요.",
        )
    }
}
