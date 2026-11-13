package io.cabincaption.phone

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.cabincaption.protocol.Wire
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 기기 TTS 엔진으로 한국어 PCM만 만든다. speak()를 쓰지 않으므로 폰 스피커로는 소리가 나지 않는다.
 * synthesizeToFile(파이프)의 끝은 onDone이 아니라 파이프 EOF로 보고, 형식은 onBeginSynthesis로 받는다(WavPcm 설명).
 */
class PhoneTts(context: Context) {

    enum class Init { PENDING, READY, FAILED }

    @Volatile
    var init: Init = Init.PENDING
        private set

    private class Format(val rate: Int, val encoding: Int, val channels: Int)

    private val formats = ConcurrentHashMap<String, Format>()
    private val ready = CountDownLatch(1)
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        init = if (status == TextToSpeech.SUCCESS) prepare() else Init.FAILED
        Log.i(TAG, "tts init status=$status -> $init")
        ready.countDown()
    }

    private fun prepare(): Init {
        val lang = tts.setLanguage(Locale.KOREA)
        Log.i(TAG, "tts engine=${tts.defaultEngine} setLanguage(ko_KR)=$lang")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                formats[utteranceId] = Format(sampleRateInHz, audioFormat, channelCount)
            }

            override fun onDone(utteranceId: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = Unit

            override fun onError(utteranceId: String, errorCode: Int) {
                Log.w(TAG, "tts id=$utteranceId error=$errorCode")
            }
        })
        return if (lang >= TextToSpeech.LANG_AVAILABLE) Init.READY else Init.FAILED
    }

    /** 막는 호출(작업 스레드에서). 실패하면 null. */
    fun synthesize(text: String, id: String): WavPcm.Pcm? {
        if (!ready.await(INIT_TIMEOUT_S, TimeUnit.SECONDS) || init != Init.READY) return null
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        val buf = ByteArrayOutputStream()
        var overflow = false
        val reader = thread(name = "tts-pipe", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        if (buf.size() + n > MAX_WAV_BYTES) {
                            overflow = true
                            break
                        }
                        buf.write(chunk, 0, n)
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "tts id=$id pipe read ${e.javaClass.simpleName}")
            }
        }
        val t0 = System.nanoTime()
        val rc = tts.synthesizeToFile(text, Bundle(), writeSide, id)
        writeSide.close()
        reader.join(if (rc == TextToSpeech.SUCCESS) SYNTH_TIMEOUT_MS else 1_000L)
        if (reader.isAlive) {
            readSide.close()
            reader.join(500)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val f = awaitFormat(id)
        val bytes = buf.toByteArray()
        val pcm = if (rc == TextToSpeech.SUCCESS && !overflow && !reader.isAlive && f != null) {
            WavPcm.fromTtsPipe(bytes, f.rate, f.encoding, f.channels)
        } else {
            null
        }
        val head = bytes.take(4).joinToString("") { "%02x".format(it) }
        Log.i(
            TAG,
            "tts id=$id rc=$rc pipeBytes=${bytes.size} head=$head begin=${f?.let { "${it.rate}/${it.encoding}/${it.channels}" }} " +
                "overflow=$overflow pcmBytes=${pcm?.data?.size} ms=$ms",
        )
        return pcm?.takeIf { it.data.isNotEmpty() }
    }

    /** onBeginSynthesis는 다른 스레드(바인더)로 온다. EOF 뒤 늦게 올 수 있어 잠깐 기다린다. */
    private fun awaitFormat(id: String): Format? {
        val deadline = System.currentTimeMillis() + FORMAT_WAIT_MS
        while (true) {
            formats.remove(id)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(10)
        }
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        const val TAG = "CabinPhone"
        const val INIT_TIMEOUT_S = 5L
        const val SYNTH_TIMEOUT_MS = 10_000L
        const val FORMAT_WAIT_MS = 1_000L
        val MAX_WAV_BYTES = Wire.sayMaxBytes(Wire.SAY_MAX_RATE) + 1024
    }
}
