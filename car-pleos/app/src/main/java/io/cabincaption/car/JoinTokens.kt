package io.cabincaption.car

import java.security.SecureRandom

/** QR에 싣는 1회 참가 토큰. 한 번에 하나만 살아 있고, 만료되거나 쓰이면 새로 만든다. 직전 만료 토큰은 EXPIRED로 구분해 알린다. */
class JoinTokens(
    private val ttlMs: Long = TTL_MS,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class Check { OK, INVALID, EXPIRED }

    private var token: String? = null
    private var expiresAt = 0L
    private var lastExpired: String? = null

    @Synchronized
    fun current(now: Long): String {
        val t = token
        if (t != null && now < expiresAt) return t
        lastExpired = t
        return rotate(now)
    }

    @Synchronized
    fun check(presented: String, now: Long): Check {
        val t = token
        if (t != null && Secrets.same(t, presented)) return if (now < expiresAt) Check.OK else Check.EXPIRED
        val old = lastExpired
        return if (old != null && Secrets.same(old, presented)) Check.EXPIRED else Check.INVALID
    }

    @Synchronized
    fun consume(now: Long) {
        lastExpired = null
        rotate(now)
    }

    @Synchronized
    fun clear() {
        token = null
        expiresAt = 0L
        lastExpired = null
    }

    private fun rotate(now: Long): String {
        val t = Secrets.newSecret(random)
        token = t
        expiresAt = now + ttlMs
        return t
    }

    companion object {
        const val TTL_MS = 120_000L
    }
}
