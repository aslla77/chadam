package io.cabincaption.phone

/**
 * 차·폰 시계 차이 추정(지연 측정용). 폰이 {t:clock}을 보내고 차가 차 시각을 돌려준다.
 * 왕복이 가장 짧았던 표본을 쓴다: 차이 = 차 시각 − (보낸 시각 + 받은 시각)/2, 오차 ≤ 왕복/2.
 */
class ClockSync(private val samplesWanted: Int = SAMPLES) {
    var offsetMs: Long? = null
        private set
    var rttMs: Long? = null
        private set
    var samples = 0
        private set

    val done: Boolean get() = samples >= samplesWanted

    fun sample(sentMs: Long, carMs: Long, receivedMs: Long) {
        val rtt = receivedMs - sentMs
        if (rtt < 0) return
        samples++
        val best = rttMs
        if (best == null || rtt < best) {
            rttMs = rtt
            offsetMs = carMs - (sentMs + receivedMs) / 2
        }
    }

    companion object {
        const val SAMPLES = 5
    }
}
