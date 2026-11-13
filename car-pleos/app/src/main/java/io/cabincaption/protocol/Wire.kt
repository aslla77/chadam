package io.cabincaption.protocol

/**
 * 차↔폰 메시지 이름(ADR-0004).
 * 폰 → 차: HELLO {token} 또는 {key}[,name]. name = 대화명([validName]). 맞지 않으면 없는 것으로 친다. 소리 보내기 = CTRL {t:say,id,rate[,text]} → PHONE_PCM(PCM16 mono LE) 여러 개 → CTRL {t:say_end,id}.
 * 단톡방식 공유: say의 text가 [validText]면 차가 그 소리를 처음 읽기 시작할 때 보낸 폰을 뺀 모든 폰에 {t:shared,id,text[,name]}를 한 번 보낸다.
 * text가 없거나 맞지 않으면 공유만 하지 않고 소리는 그대로 읽는다(옛 형식 호환). shared의 id는 차가 세션 안에서 새로 매긴다.
 * 소리 없이 보내기: 폰 → 차 {t:note,id,text}. 차 스피커를 거치지 않고 보낸 폰을 뺀 모든 폰에 바로 {t:shared,id,text}를 보내고 보낸 폰에 said done을 준다.
 * 받을 폰이 없으면 said failed,reason=nobody, text가 [validText]가 아니면 said failed,reason=format. id가 [validId]가 아니면 답하지 않는다.
 * 차 → 폰: CTRL {t:welcome,key} / {t:reject,reason} / {t:off} / {t:said,id,state[,reason]}(보낸 폰에만) / {t:speaker,on:1|0}(모든 폰).
 * said: state=waiting,reason=nav = 내비 안내가 끝나면 재생. state=failed의 reason call = 통화 중, nav = 내비 안내가 길어 못 함, nobody = note를 받을 폰이 없음. 모르는 state는 무시, 모르는 reason은 일반 실패.
 * 내비 안내 중(차 마이크 업로드 멈춤): 차 → 모든 폰 {t:nav,on:1|0}. 환영 직후에도 보낸다. 모르는 t는 무시한다(하위 호환).
 * 차 마이크 → 폰: CAR_PCM([CarChunk]: 순번·캡처 시각 + 100ms PCM16 16kHz mono). 상태는 {t:mic,silenced:1|0,input:1|0}(모든 폰).
 * 자막 멈춤: 폰 → 차 {t:pause,on:1|0}, 차 → 모든 폰 {t:pause,on:1|0}. 시계 맞추기: 폰 → 차 {t:clock}, 차 → 그 폰 {t:clock,now:<차 ms>}.
 * 살아 있음: 환영 뒤 양쪽이 [PING_MS]마다 {t:ping}을 보내고, [PEER_TIMEOUT_MS] 동안 아무 프레임도 못 받으면 끊긴 것으로 본다.
 */
object Wire {
    const val TYPE = "t"
    const val TOKEN = "token"
    const val KEY = "key"
    const val REASON = "reason"
    const val ID = "id"
    const val RATE = "rate"
    const val STATE = "state"
    const val ON = "on"
    const val SILENCED = "silenced"
    const val INPUT = "input"
    const val NOW = "now"
    const val TEXT = "text"
    const val NAME = "name"

    const val WELCOME = "welcome"
    const val REJECT = "reject"
    const val OFF = "off"
    const val SAY = "say"
    const val SAY_END = "say_end"
    const val SAID = "said"
    const val SPEAKER = "speaker"
    const val PAUSE = "pause"
    const val MIC = "mic"
    const val CLOCK = "clock"
    const val PING = "ping"
    const val NAV = "nav"
    const val SHARED = "shared"
    const val NOTE = "note"

    /** said reason: 내비 안내 때문에 기다림/실패, 통화 중이라 실패, note를 받을 폰이 없음. */
    const val SAID_NAV = "nav"
    const val SAID_CALL = "call"
    const val SAID_NOBODY = "nobody"


    const val HELLO_MAX_BODY = 1024
    const val NAME_MAX_CHARS = 12

    const val PING_MS = 2_000L
    const val PEER_TIMEOUT_MS = 6_000

    const val SAY_MAX_SECONDS = 25
    const val SAY_MIN_RATE = 8_000
    const val SAY_MAX_RATE = 48_000
    const val PCM_CHUNK = 16_000

    /** 폰 입력칸 상한(UTF-16 글자 수). */
    const val SAY_TEXT_MAX_CHARS = 100

    // UTF-16 한 글자는 UTF-8로 최대 3바이트(서로게이트 쌍은 2글자 = 4바이트)라 100자 입력은 300바이트를 넘지 않는다
    const val SAY_TEXT_MAX_BYTES = SAY_TEXT_MAX_CHARS * 3
    private const val ID_MAX = 32

    fun sayMaxBytes(rate: Int): Int = rate * 2 * SAY_MAX_SECONDS

    fun validRate(rate: Int?): Boolean = rate != null && rate in SAY_MIN_RATE..SAY_MAX_RATE

    fun validId(id: String?): Boolean =
        id != null && id.length in 1..ID_MAX && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    /** 대화명: 앞뒤 공백·제어 문자 없이 1~[NAME_MAX_CHARS]자. */
    fun validName(name: String?): Boolean =
        name != null && name.isNotBlank() && name == name.trim() && name.length <= NAME_MAX_CHARS && name.none { it.isISOControl() }

    /** 공유할 글: 공백만 있지 않고 UTF-8로 [SAY_TEXT_MAX_BYTES] 이하. */
    fun validText(text: String?): Boolean =
        text != null && text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= SAY_TEXT_MAX_BYTES

    fun hello(fields: Map<String, String>) = Frame(FrameType.HELLO, Ctrl.encode(fields))

    fun ctrl(vararg fields: Pair<String, String>) = Frame(FrameType.CTRL, Ctrl.encode(linkedMapOf(*fields)))

    fun say(id: String, rate: Int, text: String? = null) =
        if (text == null) ctrl(TYPE to SAY, ID to id, RATE to rate.toString())
        else ctrl(TYPE to SAY, ID to id, RATE to rate.toString(), TEXT to text)

    fun sayEnd(id: String) = ctrl(TYPE to SAY_END, ID to id)

    fun said(id: String, state: SayState, reason: String? = null) =
        if (reason == null) ctrl(TYPE to SAID, ID to id, STATE to state.wire)
        else ctrl(TYPE to SAID, ID to id, STATE to state.wire, REASON to reason)

    fun shared(id: String, text: String, name: String? = null) =
        if (name == null) ctrl(TYPE to SHARED, ID to id, TEXT to text) else ctrl(TYPE to SHARED, ID to id, TEXT to text, NAME to name)

    fun note(id: String, text: String) = ctrl(TYPE to NOTE, ID to id, TEXT to text)

    fun speaker(on: Boolean) = ctrl(TYPE to SPEAKER, ON to flag(on))

    fun pause(on: Boolean) = ctrl(TYPE to PAUSE, ON to flag(on))

    fun nav(on: Boolean) = ctrl(TYPE to NAV, ON to flag(on))

    /** [input] = 차 쪽 소리 입력이 돌고 있음. 0이면 권한 없음·시작 실패·입력 중단(자막이 오지 않는다). */
    fun mic(silenced: Boolean, input: Boolean) =
        ctrl(TYPE to MIC, SILENCED to flag(silenced), INPUT to flag(input))

    fun clockPing() = ctrl(TYPE to CLOCK)

    fun clockPong(now: Long) = ctrl(TYPE to CLOCK, NOW to now.toString())

    fun ping() = ctrl(TYPE to PING)

    fun flag(on: Boolean) = if (on) "1" else "0"

    /** "1"/"0"만 받는다. 그 밖의 값은 null. */
    fun parseFlag(v: String?): Boolean? = when (v) {
        "1" -> true
        "0" -> false
        else -> null
    }

    fun pcm(body: ByteArray) = Frame(FrameType.PHONE_PCM, body)
}

enum class RejectReason(val wire: String) {
    INVALID("invalid"),
    EXPIRED("expired"),
    DRIVING("driving"),
    FULL("full"),
    ;

    companion object {
        fun of(wire: String?): RejectReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** 폰이 보낸 소리의 차 쪽 상태. */
enum class SayState(val wire: String) {
    QUEUED("queued"),

    /** 차 안 다른 소리(내비 안내)가 끝나기를 기다림. 재생 중에 끊겨 다시 기다릴 수도 있다. */
    WAITING("waiting"),
    PLAYING("playing"),
    DONE("done"),
    FAILED("failed"),
    ;

    companion object {
        fun of(wire: String?): SayState? = entries.firstOrNull { it.wire == wire }
    }
}
