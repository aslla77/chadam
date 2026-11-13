package io.cabincaption.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.cabincaption.protocol.CarChunk
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 차에서 받은 PCM을 폰 인식기로 자막으로 바꾼다(ADR-0002 F1, P7 통과 경로).
 * - 입력: `ParcelFileDescriptor` 파이프 → `EXTRA_AUDIO_SOURCE`(16kHz mono PCM16). 폰 마이크를 열지 않는다.
 * - 인식기: `createOnDeviceSpeechRecognizer`만 쓴다(인식: 기기 안). 못 쓰면 서버일 수 있는 기본 인식기로 넘어가지 않고 자막을 멈춘다(ADR-0002 §5 ③·④는 오너 허용 전 사용 금지).
 * - 비분할 + 부분 결과. 세션이 끝나면 [RecoveryPolicy]대로 다시 시작하고, 그 사이 청크는 [ChunkBuffer](2초)에 모은다.
 * - 녹음 감시: 인식 중에 녹음 세션이 늘면 폰 마이크로 넘어간 것으로 보고 바로 멈춘다(조용한 마이크 폴백 차단).
 * 인식기 메서드는 메인 스레드에서만 부른다. [feed]만 아무 스레드에서 불러도 된다.
 */
class CaptionEngine(
    context: Context,
    private val listener: Listener,
    private val log: (String) -> Unit,
) {
    interface Listener {
        fun onPartial(text: String, final: Boolean)

        fun onEndOfSpeech()

        /** 기기 안 인식기로 듣는 중(true) / 기기 안 인식을 못 써 멈춤(false). */
        fun onOnDevice(active: Boolean)

        /** 사용자에게 보일 문제. null이면 문제 없음(지웠음). */
        fun onProblem(notice: RecoveryPolicy.Notice?)

        /** 인식 중 폰 녹음 세션이 늘어남 → 자막을 멈췄다. */
        fun onPhoneMic()
    }

    private enum class State { IDLE, STARTING, LISTENING, WAITING, BLOCKED, STOPPED }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = app.getSystemService(AudioManager::class.java)
    private val buffer = ChunkBuffer()
    private val kickPosted = AtomicBoolean(false)

    private var state = State.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var readEnd: ParcelFileDescriptor? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var stopped = false
    private var writer: Thread? = null
    private var errors = 0
    private var problem: RecoveryPolicy.Notice? = null
    private var sessionEndedAt = 0L

    /** 우리가 cancel()한 세션은 onError(ERROR_CLIENT)를 한 번 더 보낸다(Note20 실측). 그 오류는 세지 않는다. */
    private var cancelled = false
    private var baselineRecordings = 0

    var sessions = 0
        private set
    var phoneMicEvents = 0
        private set
    var maxRecordings = 0
        private set

    @Volatile
    var written = 0L
        private set

    val bufferDropped: Long get() = buffer.dropped

    private val recordingWatch = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = checkRecordings(configs.size, "callback")
    }

    private val readyTimeout = Runnable {
        log("asr ready timeout")
        cancel()
        sessionEnded("ready timeout")
        handleError(RecoveryPolicy.ERROR_CLIENT)
    }

    /** 메인 스레드. */
    fun start() {
        val permitted = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notice = RecoveryPolicy.startNotice(permitted, permitted && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
        if (notice != null) {
            block(notice, "cannot start: $notice")
            return
        }
        listener.onOnDevice(true)
        baselineRecordings = am.activeRecordingConfigurations.size
        maxRecordings = baselineRecordings
        am.registerAudioRecordingCallback(recordingWatch, main)
        log("asr engine start onDevice=true baselineRecordings=$baselineRecordings")
        writer = thread(name = "asr-writer", isDaemon = true) { writeLoop() }
    }

    /** 아무 스레드. 차 PCM 100ms 청크. */
    fun feed(pcm: ByteArray) {
        if (stopped) return
        buffer.offer(pcm)
        if (kickPosted.compareAndSet(false, true)) main.post(::kick)
    }

    /** 메인 스레드. 멈춤·차 스피커 재생·차 마이크 중단 → 듣던 것을 버리고, 소리가 다시 오면 새 세션으로 시작한다. */
    fun interrupt() {
        if (state == State.STARTING || state == State.LISTENING) {
            cancel()
            sessionEnded("interrupt")
            state = State.IDLE
        }
        buffer.clear()
    }

    /** 메인 스레드. 화면이 돌아왔을 때 권한 문제로 멈춘 인식을 다시 시도한다. */
    fun retry() {
        if (state != State.BLOCKED || phoneMicEvents > 0) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (writer == null) {
            state = State.IDLE
            start()
        } else {
            state = State.IDLE
            kick()
        }
    }

    /** 메인 스레드. 다시 쓰지 않는다. */
    fun stop() {
        if (stopped) return
        stopped = true
        main.removeCallbacks(readyTimeout)
        cancel()
        sessionEnded("stop")
        recognizer?.destroy()
        recognizer = null
        if (writer != null) am.unregisterAudioRecordingCallback(recordingWatch)
        writer?.interrupt()
        writer = null
        buffer.clear()
        state = State.STOPPED
        log("asr engine stop sessions=$sessions written=$written bufferDropped=${buffer.dropped} phoneMicEvents=$phoneMicEvents maxRecordings=$maxRecordings")
    }

    private fun cancel() {
        val r = recognizer ?: return
        cancelled = true
        r.cancel()
    }

    private fun kick() {
        kickPosted.set(false)
        if (state == State.IDLE && !stopped && !buffer.isEmpty()) startSession()
    }

    private fun create(): SpeechRecognizer? = try {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
    } catch (e: RuntimeException) {
        log("asr create failed ${e.javaClass.simpleName}")
        null
    }

    private fun startSession() {
        val r = recognizer ?: create()?.also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        if (r == null) {
            block(RecoveryPolicy.Notice.UNAVAILABLE, "create failed")
            return
        }
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            handleError(RecoveryPolicy.ERROR_CLIENT)
            return
        }
        readEnd = pipe[0]
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarChunk.RATE)
        sessions++
        state = State.STARTING
        try {
            r.startListening(intent)
        } catch (e: RuntimeException) {
            log("asr startListening failed ${e.javaClass.simpleName}")
            closePipe(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            sessionEnded("start failed")
            handleError(RecoveryPolicy.ERROR_CLIENT)
            return
        }
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        main.postDelayed(readyTimeout, READY_TIMEOUT_MS)
        val gap = if (sessionEndedAt > 0) " restartGapMs=${SystemClock.elapsedRealtime() - sessionEndedAt}" else ""
        log("asr session=$sessions start onDevice=true buffered=${buffer.size()}$gap")
    }

    private fun writeLoop() {
        while (!stopped) {
            val o = out
            if (o == null) {
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }
            val c = try {
                buffer.take(200)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            try {
                o.write(c)
                written++
            } catch (e: IOException) {
                if (out === o) out = null
            }
        }
    }

    private fun closePipe(o: OutputStream?) {
        try {
            o?.close()
        } catch (_: IOException) {
        }
    }

    private fun sessionEnded(why: String) {
        main.removeCallbacks(readyTimeout)
        val o = out
        out = null
        closePipe(o)
        try {
            readEnd?.close()
        } catch (_: IOException) {
        }
        readEnd = null
        sessionEndedAt = SystemClock.elapsedRealtime()
        if (state == State.STARTING || state == State.LISTENING) state = State.IDLE
        log("asr session=$sessions end why=$why")
    }

    private fun handleError(code: Int) {
        if (stopped || state == State.BLOCKED) return
        if (RecoveryPolicy.isNormal(code)) errors = 0 else errors++
        val a = RecoveryPolicy.decide(code, maxOf(errors, 1))
        log("asr error=$code consecutive=$errors -> ${a.kind} delay=${a.delayMs} notice=${a.notice}")
        if (a.notice != null) setProblem(a.notice)
        when (a.kind) {
            RecoveryPolicy.Kind.STOP -> {
                destroyRecognizer()
                block(a.notice, "error $code")
            }
            RecoveryPolicy.Kind.RECREATE -> {
                destroyRecognizer()
                later(a.delayMs)
            }
            RecoveryPolicy.Kind.RESTART -> later(a.delayMs)
        }
    }

    private fun later(delayMs: Long) {
        if (delayMs <= 0) {
            state = State.IDLE
            kick()
            return
        }
        state = State.WAITING
        main.postDelayed({
            if (state == State.WAITING && !stopped) {
                state = State.IDLE
                kick()
            }
        }, delayMs)
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun block(notice: RecoveryPolicy.Notice?, why: String) {
        state = State.BLOCKED
        buffer.clear()
        log("asr blocked: $why")
        if (notice == RecoveryPolicy.Notice.NO_ON_DEVICE || notice == RecoveryPolicy.Notice.NEED_LANGUAGE) listener.onOnDevice(false)
        setProblem(notice)
    }

    private fun setProblem(n: RecoveryPolicy.Notice?) {
        if (problem == n) return
        problem = n
        listener.onProblem(n)
    }

    private fun checkRecordings(count: Int, via: String) {
        if (count > maxRecordings) maxRecordings = count
        val active = state == State.STARTING || state == State.LISTENING
        if (count > baselineRecordings && active && !stopped) {
            phoneMicEvents++
            log("asr PHONE MIC suspected via=$via recordings=$count baseline=$baselineRecordings -> stop captions")
            cancel()
            sessionEnded("phone mic")
            destroyRecognizer()
            block(RecoveryPolicy.Notice.UNAVAILABLE, "phone mic")
            listener.onPhoneMic()
        }
    }

    private fun firstText(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            main.removeCallbacks(readyTimeout)
            // 인식기가 fd를 받은 뒤 우리 쪽 읽기 끝을 닫아야, 인식기가 읽기를 그만둘 때 쓰기 쪽이 EPIPE를 받는다(P7 probe)
            try {
                readEnd?.close()
            } catch (_: IOException) {
            }
            readEnd = null
            if (state == State.STARTING) state = State.LISTENING
            checkRecordings(am.activeRecordingConfigurations.size, "ready")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listener.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            if (cancelled && error == RecoveryPolicy.ERROR_CLIENT) {
                cancelled = false
                log("asr error=$error after our cancel, ignored")
                return
            }
            sessionEnded("error $error")
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            val t = firstText(results)
            if (t.isNotBlank()) listener.onPartial(t, true)
            sessionEnded("results")
            errors = 0
            kick()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (state == State.LISTENING || state == State.STARTING) {
                checkRecordings(am.activeRecordingConfigurations.size, "partial")
                if (state == State.BLOCKED) return
                errors = 0
                setProblem(null)
                // 기기 안 인식기는 말이 끝난 뒤 "final_result" 키가 있는 부분 결과로 그 줄을 확정한다(P7 r04 실측)
                listener.onPartial(firstText(partialResults), partialResults?.containsKey(KEY_FINAL) == true)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val READY_TIMEOUT_MS = 5_000L
        const val KEY_FINAL = "final_result"
    }
}
