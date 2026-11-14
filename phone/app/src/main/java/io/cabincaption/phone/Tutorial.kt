package io.cabincaption.phone

/** 사용설명서 장 순서와 문구. 장마다 그림은 MainActivity가 그린다. */
enum class TutorialPage(val title: Int, val detail: Int) {
    ABOUT(R.string.tut_about, R.string.tut_about_detail),
    JOIN(R.string.tut_join, R.string.tut_join_detail),
    CAPTION(R.string.tut_caption, R.string.tut_caption_detail),
    SPEAK(R.string.tut_speak, R.string.tut_speak_detail),
    SAFE(R.string.tut_safe, R.string.tut_safe_detail),
    ;

    companion object {
        fun at(index: Int): TutorialPage = entries[clamp(index)]

        fun isLast(index: Int): Boolean = clamp(index) == entries.size - 1

        fun next(index: Int): Int = clamp(index + 1)

        fun previous(index: Int): Int = clamp(index - 1)

        private fun clamp(index: Int): Int = index.coerceIn(0, entries.size - 1)
    }
}
