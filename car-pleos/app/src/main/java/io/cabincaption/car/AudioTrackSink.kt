package io.cabincaption.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 받은 PCM16 mono를 받은 샘플레이트 그대로 AudioTrack(MODE_STATIC, T-04에서 head·underrun 확인한 방식)으로 재생한다.
 * 리샘플은 플랫폼 믹서에 맡긴다. 끝은 재생 위치(head)가 전체 프레임에 닿을 때로 본다.
 * 세션마다 하나. [stop] 뒤에는 다시 재생하지 않는다. 트랙 조작은 재생 스레드에서만 한다.
 * 포커스를 못 받거나, 재생 중 어떤 형태로든 잃거나(LOSS·LOSS_TRANSIENT·LOSS_TRANSIENT_CAN_DUCK), 양보 조건이 참이면 바로 멈추고 YIELDED.
 * 덕킹으로 작게 계속 나가지 않도록 `setWillPauseWhenDucked(true)`로 덕킹도 포커스 손실 알림으로 받는다.
 * MODE_STATIC은 쓰기가 재생 전에 끝나므로 소리가 난 시점(onAudible)은 쓰기가 아니라 재생 위치(head)가 움직인 때로 본다.
 */
class AudioTrackSink(context: Context, private val log: (String) -> Unit) : PcmSink {

    private val am = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var stopped = false

    // USAGE_MEDIA: T-04 에뮬 실측에서 bus0_media_out으로 재생된 값이고, 서드파티가 쓸 수 있는 usage다(ADR-0002 [18]·[21])
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun play(pcm: ByteArray, rate: Int, id: String, yieldNow: () -> Boolean, onAudible: () -> Unit): PlayResult {
        if (stopped) return PlayResult.FAILED
        if (yieldNow()) return PlayResult.YIELDED
        val lost = AtomicBoolean(false)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                log("play id=$id focus change=$change")
                if (change in FOCUS_LOSSES) lost.set(true)
            }
            .build()
        val granted = am.requestAudioFocus(focus)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            log("play id=$id focus=$granted mode=${am.mode} -> not played")
            return PlayResult.YIELDED
        }
        val frames = pcm.size / 2
        val t0 = SystemClock.elapsedRealtime()
        var track: AudioTrack? = null
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
            track = t
            val written = t.write(pcm, 0, pcm.size)
            if (written != pcm.size) {
                log("play id=$id write=$written of ${pcm.size} -> not played")
                return PlayResult.FAILED
            }
            t.play()
            val deadline = SystemClock.elapsedRealtime() + frames * 1000L / rate + DRAIN_SLACK_MS
            var head = t.playbackHeadPosition
            var yielded = false
            var audible = false
            while (!stopped && head < frames && SystemClock.elapsedRealtime() < deadline) {
                if (lost.get() || yieldNow()) {
                    yielded = true
                    break
                }
                if (!audible && head > 0) {
                    audible = true
                    onAudible()
                }
                Thread.sleep(POLL_MS)
                head = t.playbackHeadPosition
            }
            val result = when {
                stopped -> PlayResult.FAILED
                yielded -> PlayResult.YIELDED
                head >= frames -> PlayResult.DONE
                else -> PlayResult.FAILED
            }
            // 폴링 사이에 끝까지 재생된 경우
            if (result == PlayResult.DONE && !audible) onAudible()
            log(
                "play id=$id rate=$rate frames=$frames head=$head underruns=${t.underrunCount} " +
                    "start=$t0 end=${SystemClock.elapsedRealtime()} result=$result focusLost=${lost.get()} stopped=$stopped",
            )
            return result
        } catch (e: RuntimeException) {
            log("play id=$id failed ${e.javaClass.simpleName}: ${e.message}")
            return PlayResult.FAILED
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return PlayResult.FAILED
        } finally {
            track?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            am.abandonAudioFocusRequest(focus)
        }
    }

    override fun stop() {
        stopped = true
    }

    private companion object {
        const val POLL_MS = 20L
        const val DRAIN_SLACK_MS = 3_000L
        val FOCUS_LOSSES = setOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        )
    }
}
