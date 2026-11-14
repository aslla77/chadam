package io.cabincaption.car

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 프로세스 단위로 하나. 화면이 다시 만들어져도 세션·주행 상태 구독은 그대로 둔다. */
object CarRuntime {
    private const val TAG = "CabinCar"

    @Volatile
    private var instance: SessionController? = null

    fun controller(context: Context): SessionController {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "car-session").apply { isDaemon = true } }
            val log: (String) -> Unit = { Log.i(TAG, it) }
            val priority = CarAudioPriority(app, log)
            lateinit var c: SessionController
            c = SessionController(
                newServer = {
                    CarServer(
                        lobby = Lobby(),
                        isDriving = { c.driving.blocksNewJoin },
                        speaker = SpeakerQueue(AudioTrackSink(app, log), priority, log = log),
                        log = log,
                    )
                },
                effects = AndroidEffects(app),
                runAsync = { job -> worker.execute(job) },
                // 서버 정리는 켜기 실행기와 따로 돌린다: 정리가 늦어도 다시 켜기가 막히지 않는다(H-2)
                runStop = { job -> thread(name = "car-stop", isDaemon = true) { job() } },
                localHosts = BindHost::localIpv4,
            )
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "car-auto-off").apply { isDaemon = true } }
                .scheduleWithFixedDelay({ if (c.expireIfDue()) Log.i(TAG, "auto off after ${SessionController.MAX_ON_MS}ms") }, 1, 1, TimeUnit.MINUTES)
            val signals = DrivingSignals { combined, uxr, gear ->
                Log.i(TAG, "driving=$combined (uxr=$uxr gear=$gear)")
                c.driving = combined
            }
            UxrDrivingMonitor(app) { signals.uxr(it) }.start()
            GearMonitor(app, onGear = { signals.gear(it) }).start()
            priority.onNav = { c.setNav(it) }
            priority.start()
            instance = c
            return c
        }
    }

    private class AndroidEffects(private val app: Context) : SessionController.Effects {
        private val main = Handler(Looper.getMainLooper())

        override fun chime() {
            main.post {
                try {
                    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    main.postDelayed({ tone.release() }, 400)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "chime failed: ${e.javaClass.simpleName}")
                }
            }
        }

        override fun startMic(): Boolean = try {
            app.startForegroundService(Intent(app, MicService::class.java))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForegroundService failed: ${e.javaClass.simpleName}")
            false
        }

        override fun stopMic() {
            app.stopService(Intent(app, MicService::class.java))
        }
    }
}
