package io.cabincaption.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * 차 안 다른 소리의 우선순위(ADR-0002): 통화 중이면 차 스피커로 재생하지 않고, 내비 안내 중이면 끝날 때까지 기다린다.
 * 내비 안내 중에는 차 마이크 소리도 폰에 올리지 않는다(R-28).
 */
interface AudioPriority {
    /** 통화(울림 포함) 중. */
    fun call(): Boolean

    /** 내비 안내 음성 재생 중. */
    fun nav(): Boolean

    companion object {
        val NONE = object : AudioPriority {
            override fun call() = false
            override fun nav() = false
        }

        fun isNav(usage: Int) = usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE

        fun isCall(usage: Int) =
            usage == AudioAttributes.USAGE_VOICE_COMMUNICATION || usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE

        fun isCallMode(mode: Int) =
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }
}

/**
 * 재생 구성 감시(`AudioPlaybackCallback`)와 `AudioManager.mode`로 판단한다.
 * 서드파티 앱에는 재생 중(started)인 플레이어만, usage를 남긴 익명 사본으로 온다(AOSP PlaybackActivityMonitor).
 */
class CarAudioPriority(context: Context, private val log: (String) -> Unit) : AudioPriority {
    private val am = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var navPlaying = false

    @Volatile
    private var callPlaying = false

    /** 내비 안내 재생이 시작·끝날 때 메인 스레드에서 불린다. */
    @Volatile
    var onNav: (Boolean) -> Unit = {}

    fun start() {
        val main = Handler(Looper.getMainLooper())
        am.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) = update(configs)
            },
            main,
        )
        main.post { update(am.activePlaybackConfigurations) }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        val usages = configs.map { it.audioAttributes.usage }
        val nav = usages.any(AudioPriority::isNav)
        val call = usages.any(AudioPriority::isCall)
        val navChanged = nav != navPlaying
        if (navChanged || call != callPlaying) log("audio others nav=$nav call=$call mode=${am.mode} usages=$usages")
        navPlaying = nav
        callPlaying = call
        if (navChanged) onNav(nav)
    }

    override fun call(): Boolean = callPlaying || AudioPriority.isCallMode(am.mode)

    override fun nav(): Boolean = navPlaying
}
