package io.cabincaption.phone

import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire

/**
 * debug 빌드 전용 화면 미리보기(차 연결 없이 화면 촬영): `am start … --es preview <장면> --es theme <id>`.
 * release는 [parse]가 늘 null이라 무시된다(호출부는 `BuildConfig.DEBUG`를 넘긴다).
 */
object Preview {
    enum class Scene(val key: String) {
        START("start"),
        CONSENT("consent"),
        EMPTY("empty"),
        TALK("talk"),
        BUSY("busy"),
        SILENT("silent"),
        ENDED("ended"),
        OPENING("opening"),
    }

    /** [theme]는 [Themes.isChoice]를 통과한 선택지 key(system 포함). */
    class Request(val scene: Scene?, val theme: String?)

    /** 장면·테마 둘 다 모르는 값이면 null(무시). */
    fun parse(debug: Boolean, scene: String?, theme: String?): Request? {
        if (!debug) return null
        val s = Scene.entries.firstOrNull { it.key == scene }
        val t = theme?.takeIf { Themes.isChoice(it) }
        if (s == null && t == null) return null
        return Request(s, t)
    }

    /** 목업과 같은 샘플 대화를 넣는다. 대화 화면이 아닌 장면은 아무것도 넣지 않는다. */
    fun fill(scene: Scene, captions: CaptionLog, says: SayLog) {
        when (scene) {
            Scene.TALK -> {
                captions.partial("점심은 도착해서 먹을까?", true, 0)
                captions.shared("p1", "나는 국수 먹고 싶어", "지안")
                captions.partial("좋아, 그럼 국숫집 가자", true, 0)
                mine(captions, says, "s1", "저는 비빔국수요", SayState.DONE, null)
                captions.partial("30분쯤 뒤면 도착", false, 0)
            }
            Scene.BUSY -> {
                mine(captions, says, "s1", "물 좀 주세요", SayState.FAILED, Wire.SAID_CALL)
                captions.partial("아, 미안. 전화 끊었어. 뭐라고 했어?", true, 0)
                captions.notice(CaptionLog.NoticeKind.NAV)
                mine(captions, says, "s2", "물 좀 주세요", SayState.WAITING, Wire.SAID_NAV)
            }
            Scene.SILENT -> {
                mine(captions, says, "s1", "뒷자리 창문 좀 닫아 줄래?", SayState.DONE, null, silent = true)
                mine(captions, says, "s2", "곧 휴게소 들른대", SayState.FAILED, Wire.SAID_NOBODY, silent = true)
            }
            Scene.START, Scene.CONSENT, Scene.EMPTY, Scene.ENDED, Scene.OPENING -> Unit
        }
    }

    /** 미리보기 대화 화면 입력칸의 글(목업 Main). */
    const val TALK_INPUT = "화장실 들러요"

    private fun mine(captions: CaptionLog, says: SayLog, id: String, text: String, state: SayState, reason: String?, silent: Boolean = false) {
        says.add(id, text, silent)
        says.sent(id)
        says.car(id, state, reason)
        captions.mine(id)
    }
}
