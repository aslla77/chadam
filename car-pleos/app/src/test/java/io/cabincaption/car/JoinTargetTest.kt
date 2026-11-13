package io.cabincaption.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinTargetTest {

    @Test
    fun acceptsLanIpv4() {
        assertEquals(JoinTarget("10.2.52.88", 47100), JoinTarget.parse("10.2.52.88", 47100))
        assertEquals(JoinTarget("192.168.43.1", 47000), JoinTarget.parse("192.168.43.1", 47000))
    }

    @Test
    fun rejectsUnreachableOrMalformedHosts() {
        listOf(
            null, "", "localhost", "127.0.0.1", "0.0.0.0", "10.0.2.15", "10.0.2.2", "169.254.1.1",
            "224.0.0.1", "256.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "01234.1.1.1", "1..2.3", "::1",
        ).forEach { assertNull(it, JoinTarget.parse(it, 47000)) }
    }

    @Test
    fun rejectsBadPort() {
        assertNull(JoinTarget.parse("10.2.52.88", 0))
        assertNull(JoinTarget.parse("10.2.52.88", 65536))
    }

    @Test
    fun configuredHostWinsAndIsNotSilentlyReplaced() {
        val candidates = listOf("10.0.2.15", "192.168.1.5")
        assertEquals(JoinTarget("10.2.52.88", 47100), JoinTarget.choose("10.2.52.88", candidates, 47100))
        assertNull(JoinTarget.choose("10.0.2.15", candidates, 47100))
        assertEquals(JoinTarget("192.168.1.5", 47000), JoinTarget.choose(null, candidates, 47000))
        assertNull(JoinTarget.choose(null, listOf("10.0.2.15", "127.0.0.1"), 47000))
    }

    @Test
    fun uriCarriesHostPortToken() {
        assertEquals(
            "cabincaption://join?h=10.2.52.88&p=47100&t=ab-_CD",
            JoinTarget("10.2.52.88", 47100).uri("ab-_CD"),
        )
    }

    @Test
    fun inputBlankHostMeansAutoAndBlankPortMeansDefault() {
        assertEquals(JoinTarget.Input.Auto(SessionController.LISTEN_PORT), JoinTarget.fromInput("  ", ""))
        assertEquals(JoinTarget.Input.Auto(47100), JoinTarget.fromInput("", "47100"))
    }

    @Test
    fun inputTrimsAndAcceptsValidAddress() {
        assertEquals(
            JoinTarget.Input.Valid(JoinTarget("192.168.0.10", 47000)),
            JoinTarget.fromInput(" 192.168.0.10 ", " 47000 "),
        )
    }

    @Test
    fun inputRejectsUnreachableHosts() {
        listOf("10.0.2.15", "127.0.0.1", "abc", "1.2.3").forEach {
            assertEquals(it, JoinTarget.Input.BadHost, JoinTarget.fromInput(it, "47000"))
        }
    }

    @Test
    fun rejectsLeadingZeroOctets() {
        listOf("192.168.000.010", "01.2.3.4", "192.168.0.010", "010.1.1.1").forEach {
            assertNull(it, JoinTarget.parse(it, 47000))
            assertEquals(it, JoinTarget.Input.BadHost, JoinTarget.fromInput(it, "47000"))
        }
        assertEquals(JoinTarget("192.168.0.10", 47000), JoinTarget.parse("192.168.0.10", 47000))
        assertEquals(JoinTarget("10.0.0.1", 47000), JoinTarget.parse("10.0.0.1", 47000))
    }

    @Test
    fun publicIpIsAcceptedForNow() {
        assertEquals(JoinTarget("8.8.8.8", 47000), JoinTarget.parse("8.8.8.8", 47000))
    }

    @Test
    fun portErrorWinsWhenHostAndPortBothWrong() {
        assertEquals(JoinTarget.Input.BadPort, JoinTarget.fromInput("abc", "0"))
    }

    @Test
    fun portAcceptsPlusSignAsKotlinParsesIt() {
        assertEquals(JoinTarget.Input.Valid(JoinTarget("192.168.0.10", 5)), JoinTarget.fromInput("192.168.0.10", "+5"))
    }

    @Test
    fun hostPortHasNoToken() {
        assertEquals("192.168.0.10:47000", JoinTarget("192.168.0.10", 47000).hostPort())
    }

    @Test
    fun inputRejectsBadPort() {
        listOf("0", "65536", "abc", "-1", "4.7").forEach {
            assertEquals(it, JoinTarget.Input.BadPort, JoinTarget.fromInput("192.168.0.10", it))
        }
        assertEquals(JoinTarget.Input.BadPort, JoinTarget.fromInput("", "0"))
    }
}
