package io.cabincaption.car

/**
 * 차 마이크 업로드 조건(FR-9, ADR-0002): 연결된 폰 1대 이상 && 멈춤 아님 && 차 스피커 재생 중 아님 && 내비 안내 중 아님(R-28) && 마이크가 다른 앱에 밀리지 않음.
 * 아니면 그 청크는 바로 버린다. 순번은 버린 청크에도 매긴다(폰은 순번의 빈 곳으로 끊긴 구간을 안다).
 */
class UploadGate {
    enum class Drop { NO_PHONE, PAUSED, SPEAKER, SILENCED, NAV }

    data class Counters(
        val captured: Long,
        val sent: Long,
        val noPhone: Long,
        val paused: Long,
        val speaker: Long,
        val silenced: Long,
        val nav: Long,
    ) {
        override fun toString() = "captured=$captured sent=$sent drop{noPhone=$noPhone paused=$paused speaker=$speaker nav=$nav silenced=$silenced}"
    }

    /** [send]가 참이면 [seq]로 보낸다. 아니면 [drop] 이유로 버린다. */
    data class Decision(val seq: Long, val drop: Drop?) {
        val send: Boolean get() = drop == null
    }

    private var seq = 0L
    private var sent = 0L
    private val drops = LongArray(Drop.entries.size)

    @Synchronized
    fun offer(phones: Int, paused: Boolean, speakerBusy: Boolean, nav: Boolean, silenced: Boolean): Decision {
        val drop = when {
            phones <= 0 -> Drop.NO_PHONE
            paused -> Drop.PAUSED
            speakerBusy -> Drop.SPEAKER
            nav -> Drop.NAV
            silenced -> Drop.SILENCED
            else -> null
        }
        if (drop == null) sent++ else drops[drop.ordinal]++
        return Decision(seq++, drop)
    }

    @Synchronized
    fun counters() = Counters(
        captured = seq,
        sent = sent,
        noPhone = drops[Drop.NO_PHONE.ordinal],
        paused = drops[Drop.PAUSED.ordinal],
        speaker = drops[Drop.SPEAKER.ordinal],
        silenced = drops[Drop.SILENCED.ordinal],
        nav = drops[Drop.NAV.ordinal],
    )
}
