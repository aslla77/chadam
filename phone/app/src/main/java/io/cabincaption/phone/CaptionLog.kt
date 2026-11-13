package io.cabincaption.phone

import kotlin.math.sqrt

/**
 * 대화 기록(메모리만): 앞좌석(차 마이크) 자막 줄 + 내가 보낸 글 + 다른 동승자가 보낸 글 + 끊김 알림. 나가기·차 꺼짐·연결 잃음 때 지운다.
 * 자막 줄은 부분 결과로 계속 바뀌고(진행 중), 말이 끝나면(onEndOfSpeech) 확정된다(ADR-0002 F1: 비분할 + 부분 결과).
 * 지연(B-12): 소리가 시작된 청크의 차 캡처 시각 → 그 줄의 첫 부분 결과를 화면에 낸 시각.
 */
class CaptionLog(
    private val maxEntries: Int = MAX_ENTRIES,
    private val onsetThreshold: Int = ONSET_RMS,
    private val quietChunksToRearm: Int = QUIET_CHUNKS,
) {
    sealed interface Entry {
        /** [latencyMs]는 첫 부분 결과까지 걸린 시간(차 시계 기준, 시계를 못 맞췄으면 폰이 받은 시각 기준). 모르면 null. */
        data class Line(val no: Int, val text: String, val final: Boolean, val latencyMs: Long?) : Entry

        data class Mine(val sayId: String) : Entry

        /** 다른 폰이 보낸 글(차가 읽기 시작할 때 옴). [no]는 이 폰에서 매긴 순번(스크린리더 알림용), [id]는 차가 매긴 번호. */
        data class Shared(val no: Int, val id: String, val text: String, val name: String? = null) : Entry

        data class Notice(val kind: NoticeKind) : Entry
    }

    /** [NAV] = 차에서 내비 안내가 나와 잠시 차 마이크 소리가 오지 않음. */
    enum class NoticeKind { PAUSED, RESUMED, MIC_SILENCED, MIC_BACK, NAV }

    data class Stats(val count: Int, val medianMs: Long?, val maxMs: Long?) {
        override fun toString() = "n=$count median=${medianMs}ms max=${maxMs}ms"
    }

    private class Onset(val seq: Long, val captureMs: Long, val rxMs: Long)

    private val entries = ArrayList<Entry>()
    private var nextNo = 1
    private var nextSharedNo = 1
    private var openNo: Int? = null
    private var awaitingFinalNo: Int? = null
    private var quiet = quietChunksToRearm
    private var pending: Onset? = null
    private val totals = ArrayList<Long>()
    private val recogs = ArrayList<Long>()

    /** 차 시계 − 폰 시계(ms). 시계 맞추기가 끝나면 넣는다. */
    @Volatile
    var clockOffsetMs: Long? = null

    /**
     * 받은 청크마다(연결 스레드). 조용하다가 소리가 커진 첫 청크를 말 시작으로 기억한다. 줄이 시작되면 쓰고 지운다.
     * 줄이 되지 못한 말 시작(짧은 조각)이 [STALE_ONSET_MS]보다 오래됐으면 새 말 시작으로 바꾼다.
     */
    @Synchronized
    fun chunk(seq: Long, captureMs: Long, rxMs: Long, pcm: ByteArray) {
        val loud = rms(pcm) >= onsetThreshold
        val p = pending
        if (loud && quiet >= quietChunksToRearm && (p == null || captureMs - p.captureMs > STALE_ONSET_MS)) pending = Onset(seq, captureMs, rxMs)
        quiet = if (loud) 0 else quiet + 1
    }

    /** 부분 결과. 새 줄이 시작되면 그 줄 번호를 돌려준다(= 첫 부분 결과를 낸 순간). */
    @Synchronized
    fun partial(text: String, final: Boolean, nowMs: Long): Int? {
        val t = text.trim()
        openNo?.let { no ->
            if (t.isNotEmpty()) update(no) { it.copy(text = t, final = final) } else if (final) update(no) { it.copy(final = true) }
            if (final) openNo = null
            return null
        }
        awaitingFinalNo?.let { no ->
            if (final) {
                if (t.isNotEmpty()) update(no) { it.copy(text = t) }
                awaitingFinalNo = null
                return null
            }
            // 기기 안 인식기는 말이 끝난 뒤 같은 글자의 부분 결과를 한 번 더 보내고 final을 보낸다(Note20 실측) → 새 줄이 아니다
            if (t == lineText(no)) return null
            awaitingFinalNo = null
        }
        if (t.isEmpty()) return null
        val no = nextNo++
        add(Entry.Line(no, t, final, latency(nowMs)))
        if (!final) openNo = no
        return no
    }

    /** 말이 끝남 → 지금 줄을 확정한다. 바로 뒤에 오는 final 부분 결과는 이 줄의 글자를 고친다. */
    @Synchronized
    fun endOfSpeech() {
        openNo?.let { no ->
            update(no) { it.copy(final = true) }
            awaitingFinalNo = no
        }
        openNo = null
        pending = null
    }

    /**
     * 멈춤·차 스피커 재생·마이크 중단으로 소리가 끊김 → 진행 중인 줄을 지금 글자 그대로 확정한다.
     * 말 시작 감지는 다시 무장하지 않는다: 재개 직후 첫 청크가 말 중간이면 말 시작이 아니다.
     */
    @Synchronized
    fun interrupt(notice: NoticeKind? = null) {
        openNo?.let { no -> update(no) { it.copy(final = true) } }
        openNo = null
        awaitingFinalNo = null
        pending = null
        notice?.let { add(Entry.Notice(it)) }
    }

    @Synchronized
    fun notice(kind: NoticeKind) = add(Entry.Notice(kind))

    @Synchronized
    fun mine(sayId: String) = add(Entry.Mine(sayId))

    /** 같은 [id]가 이미 있으면 넣지 않고 false. */
    @Synchronized
    fun shared(id: String, text: String, name: String? = null): Boolean {
        if (entries.any { it is Entry.Shared && it.id == id }) return false
        add(Entry.Shared(nextSharedNo++, id, text, name))
        return true
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun size(): Int = entries.size

    /** 차 캡처 → 첫 부분 결과(시계를 맞춘 경우만). */
    @Synchronized
    fun totalStats(): Stats = stats(totals)

    /** 폰이 받은 시각 → 첫 부분 결과(폰 시계만, 인식기 몫). */
    @Synchronized
    fun recognizerStats(): Stats = stats(recogs)

    @Synchronized
    fun clear() {
        entries.clear()
        nextNo = 1
        nextSharedNo = 1
        openNo = null
        awaitingFinalNo = null
        pending = null
        quiet = quietChunksToRearm
        totals.clear()
        recogs.clear()
        clockOffsetMs = null
    }

    private fun latency(nowMs: Long): Long? {
        val o = pending ?: return null
        pending = null
        val recog = nowMs - o.rxMs
        recogs += recog
        val off = clockOffsetMs ?: return recog
        val total = nowMs + off - o.captureMs
        totals += total
        return total
    }

    private fun lineText(no: Int): String? =
        entries.lastOrNull { it is Entry.Line && it.no == no }?.let { (it as Entry.Line).text }

    private fun add(e: Entry) {
        entries += e
        while (entries.size > maxEntries) entries.removeAt(0)
    }

    private fun update(no: Int, change: (Entry.Line) -> Entry.Line) {
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e is Entry.Line && e.no == no) {
                entries[i] = change(e)
                return
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 200

        /** 100ms 청크 RMS. 무음은 0, 말은 수백~수천이다. */
        const val ONSET_RMS = 300
        const val QUIET_CHUNKS = 3
        const val STALE_ONSET_MS = 2_000L

        fun rms(pcm: ByteArray): Int {
            val n = pcm.size / 2
            if (n == 0) return 0
            var sum = 0.0
            for (i in 0 until n) {
                val v = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toDouble()
                sum += v * v
            }
            return sqrt(sum / n).toInt()
        }

        fun stats(xs: List<Long>): Stats {
            if (xs.isEmpty()) return Stats(0, null, null)
            val s = xs.sorted()
            val mid = s.size / 2
            val median = if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
            return Stats(s.size, median, s.last())
        }
    }
}
