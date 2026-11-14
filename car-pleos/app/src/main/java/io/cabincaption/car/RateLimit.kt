package io.cabincaption.car

/**
 * 토큰 버킷(T5). 처음 [burst]개까지 바로 쓰고, 그 뒤로는 [refillMs]마다 1개씩 다시 찬다(최대 [burst]).
 * 한 연결의 한 메시지 종류(note·say·pause)마다 하나씩 둔다. 한 연결의 읽기 스레드에서만 부른다.
 */
class RateLimit(
    private val burst: Int = BURST,
    private val refillMs: Long = REFILL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var tokens = burst
    private var last = clock()

    /** 쓸 수 있으면 하나 쓰고 true, 넘쳤으면 false. */
    fun take(): Boolean {
        val now = clock()
        val gained = (now - last) / refillMs
        if (gained > 0) {
            tokens = minOf(burst.toLong(), tokens + gained).toInt()
            last = if (tokens == burst) now else last + gained * refillMs
        }
        if (tokens == 0) return false
        tokens--
        return true
    }

    companion object {
        /** 연달아 보낼 수 있는 수. 사람이 몇 개를 빠르게 보내도 막히지 않는다. */
        const val BURST = 5

        /** 1초에 1개씩 다시 찬다. 100자 글을 쓰거나 TTS로 만드는 데 1초보다 오래 걸린다. */
        const val REFILL_MS = 1_000L
    }
}
