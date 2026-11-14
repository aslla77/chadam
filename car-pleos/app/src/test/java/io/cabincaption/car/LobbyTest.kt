package io.cabincaption.car

import io.cabincaption.protocol.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LobbyTest {

    private val lobby = Lobby(JoinTokens(ttlMs = 1_000), maxPhones = 2, keyAbsentMs = 10_000)

    private fun join(now: Long = 0, driving: Boolean = false, conn: Long = 1): Admit =
        lobby.admit(mapOf("token" to lobby.token(now)), conn, driving, now)

    private fun rejected(a: Admit) = (a as Admit.Rejected).reason

    @Test
    fun validTokenJoinsAndGetsReconnectKey() {
        val a = join() as Admit.Accepted
        assertEquals(22, a.key.length)
        assertNull(a.replacedConn)
        assertEquals(1, lobby.phones())
    }

    @Test
    fun wrongTokenIsRejected() {
        lobby.token(0)
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("token" to "wrong"), 1, false, 0)))
        assertEquals(0, lobby.phones())
    }

    @Test
    fun usedTokenIsRejectedOnReuse() {
        val t = lobby.token(0)
        assertTrue(lobby.admit(mapOf("token" to t), 1, false, 0) is Admit.Accepted)
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("token" to t), 2, false, 1)))
    }

    @Test
    fun expiredTokenIsRejected() {
        val t = lobby.token(0)
        assertEquals(RejectReason.EXPIRED, rejected(lobby.admit(mapOf("token" to t), 1, false, 1_000)))
    }

    @Test
    fun expiredTokenStaysExpiredAfterQrRefresh() {
        val t = lobby.token(0)
        lobby.token(1_000)
        assertEquals(RejectReason.EXPIRED, rejected(lobby.admit(mapOf("token" to t), 1, false, 1_001)))
    }

    @Test
    fun drivingRejectsNewJoinWithoutBurningToken() {
        val t = lobby.token(0)
        assertEquals(RejectReason.DRIVING, rejected(lobby.admit(mapOf("token" to t), 1, true, 10)))
        assertTrue(lobby.admit(mapOf("token" to t), 1, false, 20) is Admit.Accepted)
    }

    @Test
    fun wrongTokenWhileDrivingSaysInvalidNotDriving() {
        lobby.token(0)
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("token" to "x"), 1, true, 0)))
    }

    @Test
    fun capacityIsEnforced() {
        assertTrue(join(conn = 1) is Admit.Accepted)
        assertTrue(join(conn = 2) is Admit.Accepted)
        assertEquals(RejectReason.FULL, rejected(join(conn = 3)))
        assertEquals(2, lobby.phones())
    }

    @Test
    fun reconnectKeyWorksWhileDrivingReplacesOldConnectionAndRotates() {
        val key = (join(conn = 1) as Admit.Accepted).key
        val again = lobby.admit(mapOf("key" to key), 2, true, 50) as Admit.Accepted
        assertTrue(again.key != key)
        assertEquals(22, again.key.length)
        assertEquals(1L, again.replacedConn)
        assertTrue(again.rejoin)
        assertEquals(1, lobby.phones())
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to key), 3, false, 60)))
        assertTrue(lobby.admit(mapOf("key" to again.key), 3, false, 60) is Admit.Accepted)
    }

    // 단톡방식 공유에서 "보낸 폰"을 재접속 뒤에도 같은 폰으로 알아보는 번호
    @Test
    fun memberStaysTheSameAcrossReconnectsAndDiffersBetweenPhones() {
        val a = join(conn = 1) as Admit.Accepted
        val b = join(conn = 2) as Admit.Accepted
        assertEquals(1L, a.member)
        assertEquals(2L, b.member)
        lobby.left(a.key, 1, 0)
        val a2 = lobby.admit(mapOf("key" to a.key), 5, true, 10) as Admit.Accepted
        val a3 = lobby.admit(mapOf("key" to a2.key), 6, false, 20) as Admit.Accepted
        assertEquals(1L, a2.member)
        assertEquals(1L, a3.member)
    }

    @Test
    fun staleConnectionCloseDoesNotDropReplacement() {
        val key = (join(conn = 1) as Admit.Accepted).key
        val k2 = (lobby.admit(mapOf("key" to key), 2, false, 0) as Admit.Accepted).key
        lobby.left(key, 1, 0)
        assertEquals(1, lobby.phones())
        lobby.left(k2, 2, 0)
        assertEquals(0, lobby.phones())
    }

    @Test
    fun keyIsForgottenAfterBeingAbsentTooLong() {
        val key = (join(conn = 1) as Admit.Accepted).key
        lobby.left(key, 1, 1_000)
        val back = lobby.admit(mapOf("key" to key), 2, true, 10_999) as Admit.Accepted
        lobby.left(back.key, 2, 20_000)
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to back.key), 3, false, 30_000)))
    }

    // T2: 나가기(bye)는 2분 재접속 기한을 두지 않고 키를 바로 버린다. 그냥 끊긴 키는 기한 안에 다시 붙는다
    @Test
    fun byeForgetsKeyAtOnceButPlainDisconnectKeepsIt() {
        val a = (join(conn = 1) as Admit.Accepted).key
        val b = (join(conn = 2) as Admit.Accepted).key
        lobby.bye(a, 1)
        lobby.left(b, 2, 0)
        assertEquals(0, lobby.phones())
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to a), 3, false, 1)))
        assertTrue(lobby.admit(mapOf("key" to b), 4, false, 1) is Admit.Accepted)
    }

    @Test
    fun byeFromReplacedConnectionDoesNotDropTheNewKey() {
        val key = (join(conn = 1) as Admit.Accepted).key
        val k2 = (lobby.admit(mapOf("key" to key), 2, false, 0) as Admit.Accepted).key
        lobby.bye(key, 1)
        lobby.bye(k2, 1)
        assertEquals(1, lobby.phones())
        lobby.left(k2, 2, 0)
        assertTrue(lobby.admit(mapOf("key" to k2), 3, false, 1) is Admit.Accepted)
    }

    // T3: 내보내면 그 폰의 키는 연결 중이든 끊긴 채 기다리든 바로 버린다. 다른 폰은 그대로다
    @Test
    fun kickForgetsTheMembersKeyAtOnceAndLeavesOthers() {
        val a = join(conn = 1) as Admit.Accepted
        val b = join(conn = 2) as Admit.Accepted
        assertTrue(lobby.holds(a.key, 1))
        assertTrue(lobby.kick(a.member))
        assertFalse(lobby.holds(a.key, 1))
        assertEquals(1, lobby.phones())
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to a.key), 3, false, 1)))
        assertTrue(lobby.holds(b.key, 2))
        assertFalse(lobby.kick(a.member))
    }

    @Test
    fun kickAlsoForgetsAKeyWaitingForRejoinAndTheRotatedOne() {
        val a = join(conn = 1) as Admit.Accepted
        val a2 = lobby.admit(mapOf("key" to a.key), 2, false, 0) as Admit.Accepted
        lobby.left(a2.key, 2, 0)
        assertTrue(lobby.kick(a.member))
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to a2.key), 3, false, 1)))
        lobby.left(a2.key, 2, 1)
        assertEquals(0, lobby.phones())
    }

    @Test
    fun connectedKeyDoesNotExpire() {
        val key = (join(conn = 1) as Admit.Accepted).key
        assertTrue(lobby.admit(mapOf("key" to key), 2, false, 1_000_000) is Admit.Accepted)
    }

    @Test
    fun reconnectAfterDisconnectRespectsCapacity() {
        val k1 = (join(conn = 1) as Admit.Accepted).key
        lobby.left(k1, 1, 0)
        join(conn = 2)
        join(conn = 3)
        assertEquals(RejectReason.FULL, rejected(lobby.admit(mapOf("key" to k1), 4, false, 0)))
    }

    @Test
    fun unknownKeyAndMalformedHelloAreInvalid() {
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to "nope"), 1, false, 0)))
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(emptyMap(), 1, false, 0)))
        val t = lobby.token(0)
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("token" to t, "key" to "k"), 1, false, 0)))
    }

    @Test
    fun clearForgetsKeysTokensAndConnections() {
        val t0 = lobby.token(0)
        val key = (lobby.admit(mapOf("token" to t0), 1, false, 0) as Admit.Accepted).key
        val t1 = lobby.token(0)
        lobby.clear()
        assertEquals(0, lobby.phones())
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("key" to key), 2, false, 0)))
        assertEquals(RejectReason.INVALID, rejected(lobby.admit(mapOf("token" to t1), 2, false, 0)))
    }
}
