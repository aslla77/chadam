package io.cabincaption.phone

import io.cabincaption.phone.CaptionLog.Entry

/** 대화 말풍선의 판단(어느 쪽·설명 문구·스크롤)만 모은 순수 함수. 뷰는 MainActivity가 만든다. */
object Bubbles {
    enum class Side { LEFT, RIGHT, CENTER }

    /** 차 자막·다른 동승자 글 = 왼쪽(라벨로 구분), 내가 보낸 글 = 오른쪽, 알림 = 가운데. */
    fun side(e: Entry): Side = when (e) {
        is Entry.Line, is Entry.Shared -> Side.LEFT
        is Entry.Mine -> Side.RIGHT
        is Entry.Notice -> Side.CENTER
    }

    /** [carPartial]·[car]는 `%1$s` 자리가 있는 문구, [mine]은 `%1$s`(글)·`%2$s`(상태), [shared]는 `%1$s`(대화명)·`%2$s`(글). */
    class Templates(val car: String, val carPartial: String, val mine: String, val shared: String)

    fun carDescription(line: Entry.Line, t: Templates): String =
        (if (line.final) t.car else t.carPartial).format(line.text)

    fun mineDescription(text: String, step: String, t: Templates): String = t.mine.format(text, step)

    fun sharedDescription(e: Entry.Shared, unnamed: String, t: Templates): String = t.shared.format(sharedLabel(e, unnamed), e.text)

    /** 다른 동승자 글의 라벨. 대화명이 없으면 [unnamed]. */
    fun sharedLabel(e: Entry.Shared, unnamed: String): String = e.name ?: unnamed

    /** 맨 아래에서 [thresholdPx] 안에 있을 때만 새 항목을 따라간다(위로 올려 읽는 중이면 그대로 둔다). */
    fun followsBottom(scrollY: Int, viewportH: Int, contentH: Int, thresholdPx: Int): Boolean =
        contentH <= viewportH || scrollY + viewportH >= contentH - thresholdPx

    /** 뷰를 다시 만든 뒤 돌아갈 스크롤 위치. 맨 아래였으면 null(계속 맨 아래), 위로 올려 읽는 중이었으면 [scrollY]. */
    fun restoredScrollY(scrollY: Int, viewportH: Int, contentH: Int, thresholdPx: Int): Int? =
        if (followsBottom(scrollY, viewportH, contentH, thresholdPx)) null else scrollY

    fun maxBubbleWidth(containerPx: Int): Int = (containerPx * MAX_WIDTH_RATIO).toInt()

    /** 스크린리더에 읽어 줄 새로 확정된 차 자막 줄. 진행 중 줄은 읽지 않는다. */
    fun newlyFinal(entries: List<Entry>, lastAnnouncedNo: Int): List<Entry.Line> =
        entries.filterIsInstance<Entry.Line>().filter { it.final && it.no > lastAnnouncedNo }

    /** 스크린리더에 읽어 줄 새로 받은 다른 동승자 글. */
    fun newlyShared(entries: List<Entry>, lastAnnouncedNo: Int): List<Entry.Shared> =
        entries.filterIsInstance<Entry.Shared>().filter { it.no > lastAnnouncedNo }

    const val MAX_WIDTH_RATIO = 0.78f
}
