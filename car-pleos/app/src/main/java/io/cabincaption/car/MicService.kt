package io.cabincaption.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 마이크 포그라운드 서비스. 켜져 있는 동안 프로세스(와 TCP 서버)를 유지하고 소리 입력([CarAudioSource])을 돌린다.
 * 입력은 세션 내내 켜 두고 업로드 단계에서만 막는다(ADR-0002 RT-10). 디스크에 쓰지 않는다.
 */
class MicService : Service() {

    private var source: CarAudioSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mic_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.mic_notification))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            Log.i(TAG, "mic FGS started")
        } catch (e: RuntimeException) {
            Log.w(TAG, "mic FGS failed: ${e.javaClass.simpleName}")
            CarRuntime.controller(this).setMicInput(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (source == null) {
            val log: (String) -> Unit = { Log.i(TAG, it) }
            val c = CarRuntime.controller(this)
            val s = MicAudioSource(this, log)
            // 시작 직후 곧바로 실패해도 마지막 상태가 "입력 없음"이 되도록 같은 잠금 안에서 알린다
            val lock = Any()
            var failed = false
            val ok = s.start(
                onChunk = { pcm, at -> c.carPcm(pcm, at) },
                onSilenced = { c.setMicSilenced(it) },
                onFailed = {
                    synchronized(lock) {
                        failed = true
                        c.setMicInput(false)
                    }
                },
            )
            Log.i(TAG, "mic source started=$ok")
            synchronized(lock) { c.setMicInput(ok && !failed) }
            if (ok) source = s
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        source?.stop()
        source = null
        Log.i(TAG, "mic FGS stopped")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CabinCar"
        const val CHANNEL = "session"
        const val NOTIFICATION_ID = 1
    }
}
