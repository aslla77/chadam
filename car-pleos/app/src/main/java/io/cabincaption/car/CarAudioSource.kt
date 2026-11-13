package io.cabincaption.car

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import io.cabincaption.protocol.CarChunk
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/** 차 쪽 소리 입력(ADR-0002 "허용하는 추상화"): 차 마이크. 100ms(3,200B)씩 캡처 스레드에서 넘긴다. */
interface CarAudioSource {
    /**
     * 시작하면 true. [onChunk](PCM, 캡처 시각 ms)는 캡처 스레드에서, [onSilenced]는 무음 처리 상태가 바뀔 때 불린다.
     * [onFailed]는 [stop] 없이 입력이 멈췄을 때(다시 열기 실패·예외) 한 번 불린다.
     */
    fun start(onChunk: (ByteArray, Long) -> Unit, onSilenced: (Boolean) -> Unit, onFailed: () -> Unit): Boolean

    fun stop()
}

/** 차 마이크 `AudioRecord`(VOICE_RECOGNITION, 16kHz mono PCM16; P2 근거). 무음 처리는 AudioRecordingCallback으로만 알 수 있다(P2b). */
class MicAudioSource(private val context: Context, private val log: (String) -> Unit) : CarAudioSource {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val callbacks = Executors.newSingleThreadExecutor { r -> Thread(r, "car-mic-cb").apply { isDaemon = true } }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(CarChunk.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                CarChunk.RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CarChunk.CHUNK_BYTES * 4),
            )
        } catch (e: RuntimeException) {
            log("mic open failed ${e.javaClass.simpleName}")
            return null
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            log("mic open failed state=${r.state}")
            r.release()
            return null
        }
        return r
    }

    override fun start(onChunk: (ByteArray, Long) -> Unit, onSilenced: (Boolean) -> Unit, onFailed: () -> Unit): Boolean {
        if (running) return true
        val first = open() ?: return false
        running = true
        worker = thread(name = "car-mic", isDaemon = true) { loop(first, onChunk, onSilenced, onFailed) }
        return true
    }

    private fun loop(first: AudioRecord, onChunk: (ByteArray, Long) -> Unit, onSilenced: (Boolean) -> Unit, onFailed: () -> Unit) {
        var r: AudioRecord? = first
        var reopens = 0
        var silenced = false
        val watch = { configs: List<AudioRecordingConfiguration> ->
            val cur = r
            if (cur != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == cur.audioSessionId }
                val now = mine?.isClientSilenced ?: false
                if (now != silenced) {
                    silenced = now
                    log("mic silenced=$now")
                    onSilenced(now)
                }
            }
        }
        // 콜백은 메인 루퍼에서 온다. onSilenced는 폰에 보내므로(네트워크) 별도 스레드에서 처리한다
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val copy = configs.toList()
                try {
                    callbacks.execute { watch(copy) }
                } catch (_: RejectedExecutionException) {
                }
            }
        }
        val am = context.getSystemService(AudioManager::class.java)
        am.registerAudioRecordingCallback(cb, null)
        var failed = false
        try {
            while (running) {
                val rec = r ?: break
                try {
                    rec.startRecording()
                } catch (e: IllegalStateException) {
                    log("mic startRecording failed ${e.javaClass.simpleName}")
                    break
                }
                log("mic recording state=${rec.recordingState} session=${rec.audioSessionId}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        callbacks.execute { watch(am.activeRecordingConfigurations) }
                    } catch (_: RejectedExecutionException) {
                    }
                }
                while (running) {
                    val buf = ByteArray(CarChunk.CHUNK_BYTES)
                    var off = 0
                    while (off < buf.size && running) {
                        val n = rec.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    if (!running) break
                    if (off < buf.size) {
                        log("mic read error, reopening")
                        break
                    }
                    reopens = 0
                    onChunk(buf, System.currentTimeMillis())
                }
                try {
                    rec.stop()
                } catch (_: IllegalStateException) {
                }
                rec.release()
                r = null
                // ADR-0002: 오류면 release() 뒤 새 AudioRecord (백그라운드에서도 됨, P2b). 연달아 3번 실패하면 그만둔다
                if (running && reopens++ < 3) {
                    SystemClock.sleep(200)
                    r = open()
                }
            }
        } catch (e: RuntimeException) {
            log("mic loop error ${e.javaClass.simpleName}")
        } finally {
            am.unregisterAudioRecordingCallback(cb)
            r?.release()
            // stop() 없이 여기 왔으면 입력이 멈춘 것이다(다시 열기 3번 실패·예외)
            failed = running
            running = false
            log("mic loop ended failed=$failed")
        }
        if (failed) onFailed()
    }

    override fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
        callbacks.shutdown()
    }
}
