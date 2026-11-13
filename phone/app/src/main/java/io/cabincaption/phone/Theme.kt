package io.cabincaption.phone

/** 화면 색 6종. [key]는 설정(SharedPreferences)·debug 미리보기 extras에 쓰는 이름. */
enum class ThemeId(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    HYUNDAI("hyundai"),
    SAND("sand"),
    KIA("kia"),
    CONTRAST("contrast"),
}

/** 화면 색 토큰. 모든 뷰는 이 값만 쓴다. [surfaceBorder]가 null이면 차 말풍선·알림에 테두리가 없다. */
class Palette(
    val bg: Int,
    val surface: Int,
    val surfaceBorder: Int?,
    val field: Int,
    val line: Int,
    val border: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
    val onAccent: Int,
    val notice: Int,
    val noticeText: Int,
    val shared: Int,
)

/** 값은 오너가 승인한 폰 목업(2026-11-03, `*.dc.html`의 THEMES)을 그대로 옮겼다. */
object Themes {
    /** 선택지 맨 앞의 "기기 설정 따라가기". 저장값이 없거나 모르는 값이어도 이쪽이다. */
    const val SYSTEM = "system"
    const val SURFACE_BORDER_DP = 2f

    fun find(key: String?): ThemeId? = ThemeId.entries.firstOrNull { it.key == key }

    /** 저장값을 선택지 key로 정리한다. 모르는 값은 [SYSTEM]. */
    fun choice(pref: String?): String = if (find(pref) != null) pref!! else SYSTEM

    /** 선택지 + 기기 야간 모드 → 실제 팔레트. 명시 선택은 기기 모드를 무시한다. */
    fun resolve(pref: String?, deviceNight: Boolean): ThemeId =
        find(pref) ?: if (deviceNight) ThemeId.DARK else ThemeId.LIGHT

    /** 저장·미리보기에 쓸 수 있는 선택지 key인지. */
    fun isChoice(key: String?): Boolean = key == SYSTEM || find(key) != null

    fun palette(id: ThemeId): Palette = when (id) {
        ThemeId.DARK -> Palette(
            bg = c(0x000000), surface = c(0x1F1F1F), surfaceBorder = null, field = c(0x111111), line = c(0x2A2A2A),
            border = c(0x636363), text = c(0xFFFFFF), sub = c(0xA8A8A8), accent = c(0xFFFFFF), onAccent = c(0x000000),
            notice = c(0x161616), noticeText = c(0xD0D0D0), shared = c(0x6A6A6A),
        )
        ThemeId.LIGHT -> Palette(
            bg = c(0xFFFFFF), surface = c(0xEFEFEF), surfaceBorder = null, field = c(0xF6F6F6), line = c(0xE0E0E0),
            border = c(0x8A8A8A), text = c(0x111111), sub = c(0x5C5C5C), accent = c(0x111111), onAccent = c(0xFFFFFF),
            notice = c(0xF2F2F2), noticeText = c(0x333333), shared = c(0x767676),
        )
        ThemeId.HYUNDAI -> Palette(
            bg = c(0x002C5F), surface = c(0x0D3B70), surfaceBorder = null, field = c(0x00234C), line = c(0x1C4A7E),
            border = c(0x5D80A8), text = c(0xFFFFFF), sub = c(0xAACAE6), accent = c(0x00AAD2), onAccent = c(0x00152E),
            notice = c(0x0A3567), noticeText = c(0xD6E6F4), shared = c(0xAACAE6),
        )
        ThemeId.SAND -> Palette(
            bg = c(0xF6F3F2), surface = c(0xE4DCD3), surfaceBorder = null, field = c(0xFFFFFF), line = c(0xE4DCD3),
            border = c(0x8C8279), text = c(0x002C5F), sub = c(0x5E5650), accent = c(0x002C5F), onAccent = c(0xFFFFFF),
            notice = c(0xEDE7E1), noticeText = c(0x3A342F), shared = c(0xA36B4F),
        )
        ThemeId.KIA -> Palette(
            bg = c(0x05141F), surface = c(0x13242F), surfaceBorder = null, field = c(0x0B1C28), line = c(0x1E2F3A),
            border = c(0x5E6C77), text = c(0xFFFFFF), sub = c(0xA9AFB2), accent = c(0xFFFFFF), onAccent = c(0x05141F),
            notice = c(0x0F1F2A), noticeText = c(0xD3D7D9), shared = c(0x8C9091),
        )
        ThemeId.CONTRAST -> Palette(
            bg = c(0x000000), surface = c(0x000000), surfaceBorder = c(0xFFFFFF), field = c(0x000000), line = c(0xFFFFFF),
            border = c(0xFFFFFF), text = c(0xFFFFFF), sub = c(0xFFFFFF), accent = c(0xFFD54F), onAccent = c(0x000000),
            notice = c(0x000000), noticeText = c(0xFFD54F), shared = c(0xFFD54F),
        )
    }

    /** 밝은 바탕이면 상태 표시줄 아이콘을 어둡게 한다. */
    fun isLight(p: Palette): Boolean = luminance(p.bg) > 0.5

    /** WCAG 2.x 상대 휘도(sRGB). */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun c(rgb: Int): Int = (0xFF000000 or rgb.toLong()).toInt()
}
