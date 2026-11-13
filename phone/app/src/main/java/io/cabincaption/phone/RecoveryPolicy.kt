package io.cabincaption.phone

/**
 * 인식기 오류 코드별 대응(ADR-0002 "인식기 오류 코드별 대응", 값은 android.speech.SpeechRecognizer.ERROR_*).
 * [consecutive]는 이번 오류를 포함한 연속 오류 횟수(1부터). 정상 범위(6·7)는 세지 않는다.
 * 인식기는 기기 안 인식기 하나뿐이다(오너 결정 4 전 안전 기본값, 동의 문구 "이 폰 안에서"). 서버일 수 있는 기본 인식기로 넘어가지 않고,
 * 기기 안 인식을 못 쓰면(시작 불가·12·13) 자막을 멈추고 알린다.
 */
object RecoveryPolicy {
    enum class Kind {
        /** [Action.delayMs] 뒤 새 파이프로 다시 듣는다. */
        RESTART,

        /** 인식기 객체를 새로 만들어 다시 듣는다. */
        RECREATE,

        /** 다시 시도하지 않는다(사용자 조치 필요). */
        STOP,
    }

    /** [NO_ON_DEVICE] = 이 폰에서 기기 안 인식을 쓸 수 없어 자막을 멈춤(소리를 서버로 보내지 않음). */
    enum class Notice { SLOW, NEED_PERMISSION, NEED_LANGUAGE, NO_ON_DEVICE, UNSTABLE, UNAVAILABLE }

    data class Action(val kind: Kind, val delayMs: Long = 0, val notice: Notice? = null)

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    const val BUSY_DELAY_MS = 300L
    const val GIVE_UP_AFTER = 3
    const val PAUSE_AFTER_GIVE_UP_MS = 5_000L

    fun isNormal(error: Int) = error == ERROR_SPEECH_TIMEOUT || error == ERROR_NO_MATCH

    /** 인식을 시작해도 되면 null, 아니면 멈추고 보여 줄 안내. */
    fun startNotice(permitted: Boolean, onDeviceAvailable: Boolean): Notice? = when {
        !permitted -> Notice.NEED_PERMISSION
        !onDeviceAvailable -> Notice.NO_ON_DEVICE
        else -> null
    }

    /** 1→2→4→8초, 그 뒤 8초. */
    fun backoffMs(consecutive: Int): Long = 1_000L shl (consecutive - 1).coerceIn(0, 3)

    fun decide(error: Int, consecutive: Int): Action = when (error) {
        ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> Action(Kind.RESTART)
        ERROR_RECOGNIZER_BUSY -> Action(Kind.RESTART, BUSY_DELAY_MS)
        ERROR_TOO_MANY_REQUESTS -> Action(Kind.RESTART, backoffMs(consecutive), Notice.SLOW)
        ERROR_INSUFFICIENT_PERMISSIONS -> Action(Kind.STOP, notice = Notice.NEED_PERMISSION)
        ERROR_LANGUAGE_UNAVAILABLE -> Action(Kind.STOP, notice = Notice.NEED_LANGUAGE)
        ERROR_LANGUAGE_NOT_SUPPORTED -> Action(Kind.STOP, notice = Notice.NO_ON_DEVICE)
        // 폴백 표의 다음 경로(P8)는 아직 없으므로 3회 연속이면 잠시 쉬었다가 다시 만든다
        ERROR_SERVER_DISCONNECTED ->
            if (consecutive >= GIVE_UP_AFTER) Action(Kind.RECREATE, PAUSE_AFTER_GIVE_UP_MS, Notice.UNAVAILABLE) else Action(Kind.RECREATE)
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER -> Action(Kind.RESTART, backoffMs(consecutive), Notice.UNSTABLE)
        else -> // ERROR_AUDIO, ERROR_CLIENT, 모르는 값
            if (consecutive >= GIVE_UP_AFTER) Action(Kind.RESTART, PAUSE_AFTER_GIVE_UP_MS, Notice.UNAVAILABLE) else Action(Kind.RESTART)
    }
}
