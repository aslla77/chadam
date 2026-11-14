package io.cabincaption.car

import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.Wire
import java.security.SecureRandom

sealed interface Admit {
    /**
     * [key]는 이 연결의 재접속 키(재접속이면 새로 바꾼 키). [replacedConn]은 같은 키로 붙어 있던 이전 연결(있으면 닫는다).
     * [member]는 같은 폰이면 재접속해도 그대로인 번호(처음 붙은 연결 번호).
     */
    data class Accepted(val key: String, val replacedConn: Long?, val member: Long, val rejoin: Boolean = false) : Admit

    data class Rejected(val reason: RejectReason) : Admit
}

/**
 * 참가 판정. 새 참가 = 토큰(정차 중만), 재접속 = 세션 재접속 키(주행 중에도). 동시 연결은 [maxPhones]대까지.
 * 재접속 키는 쓸 때마다 새 키로 바꾸고(옛 키는 폐기), 연결이 끊긴 채 [keyAbsentMs]가 지나면 버린다(M-2).
 */
class Lobby(
    private val tokens: JoinTokens = JoinTokens(),
    private val maxPhones: Int = MAX_PHONES,
    private val random: SecureRandom = SecureRandom(),
    private val keyAbsentMs: Long = KEY_ABSENT_MS,
) {
    /** 키 → 끊긴 시각(연결돼 있으면 null). */
    private val keys = LinkedHashMap<String, Long?>()
    private val live = HashMap<String, Long>()
    private val members = HashMap<String, Long>()

    @Synchronized
    fun admit(hello: Map<String, String>, connId: Long, driving: Boolean, now: Long): Admit {
        val token = hello[Wire.TOKEN]
        val key = hello[Wire.KEY]
        return when {
            token != null && key == null -> join(token, connId, driving, now)
            key != null && token == null -> rejoin(key, connId, now)
            else -> Admit.Rejected(RejectReason.INVALID)
        }
    }

    private fun join(token: String, connId: Long, driving: Boolean, now: Long): Admit {
        when (tokens.check(token, now)) {
            JoinTokens.Check.INVALID -> return Admit.Rejected(RejectReason.INVALID)
            JoinTokens.Check.EXPIRED -> return Admit.Rejected(RejectReason.EXPIRED)
            JoinTokens.Check.OK -> Unit
        }
        if (driving) return Admit.Rejected(RejectReason.DRIVING)
        if (live.size >= maxPhones) return Admit.Rejected(RejectReason.FULL)
        tokens.consume(now)
        val k = Secrets.newSecret(random)
        keys[k] = null
        live[k] = connId
        members[k] = connId
        return Admit.Accepted(k, replacedConn = null, member = connId)
    }

    private fun rejoin(key: String, connId: Long, now: Long): Admit {
        forgetAbsent(now)
        var known: String? = null
        for (k in keys.keys) if (Secrets.same(k, key)) known = k
        if (known == null) return Admit.Rejected(RejectReason.INVALID)
        val prev = live[known]
        if (prev == null && live.size >= maxPhones) return Admit.Rejected(RejectReason.FULL)
        keys.remove(known)
        live.remove(known)
        val member = members.remove(known) ?: connId
        val fresh = Secrets.newSecret(random)
        keys[fresh] = null
        live[fresh] = connId
        members[fresh] = member
        return Admit.Accepted(fresh, replacedConn = prev, member = member, rejoin = true)
    }

    private fun forgetAbsent(now: Long) {
        keys.entries.removeIf { (k, absentSince) -> (absentSince != null && now - absentSince >= keyAbsentMs).also { if (it) members.remove(k) } }
    }

    /** 연결 [connId]가 끝났다. 그 키가 아직 이 연결 것이면 "끊긴 시각"을 적는다(재접속 기한 시작). */
    @Synchronized
    fun left(key: String, connId: Long, now: Long) {
        if (live[key] != connId) return
        live.remove(key)
        if (keys.containsKey(key)) keys[key] = now
    }

    /** 연결 [connId]의 폰이 나가기(bye)를 보냈다. 그 키가 아직 이 연결 것이면 재접속 기한 없이 바로 버린다(T2). */
    @Synchronized
    fun bye(key: String, connId: Long) {
        if (live[key] != connId) return
        live.remove(key)
        keys.remove(key)
        members.remove(key)
    }

    /** 차 화면에서 폰 [member]를 내보냈다(T3). 그 폰의 재접속 키를 모두 바로 버린다. 버린 것이 있으면 true. */
    @Synchronized
    fun kick(member: Long): Boolean {
        val gone = members.filterValues { it == member }.keys
        gone.forEach {
            keys.remove(it)
            live.remove(it)
            members.remove(it)
        }
        return gone.isNotEmpty()
    }

    /** [key]가 아직 연결 [connId]의 것인지(내보내기·재접속으로 바뀌지 않았는지). */
    @Synchronized
    fun holds(key: String, connId: Long): Boolean = live[key] == connId

    @Synchronized
    fun phones(): Int = live.size

    @Synchronized
    fun token(now: Long): String = tokens.current(now)

    @Synchronized
    fun clear() {
        keys.clear()
        live.clear()
        members.clear()
        tokens.clear()
    }

    companion object {
        const val MAX_PHONES = 4

        /** 끊긴 뒤 재접속 키를 받아 주는 시간. 폰의 재접속 시도(약 12초)보다 넉넉하다. */
        const val KEY_ABSENT_MS = 120_000L
    }
}
