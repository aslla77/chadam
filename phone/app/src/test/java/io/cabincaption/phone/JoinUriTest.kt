package io.cabincaption.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JoinUriTest {
    private val tok = "AbCdEfGhIjKlMnOpQrSt_-"
    private val pin = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"

    private fun link(h: String, p: String = "47000") = "cabincaption://join2?h=$h&p=$p&t=$tok&f=$pin"

    @Test
    fun parsesCarQr() {
        assertEquals(JoinUri("10.2.52.88", 47100, tok, pin), JoinUri.parse("cabincaption://join2?h=10.2.52.88&p=47100&t=$tok&f=$pin"))
        assertEquals(JoinUri("192.168.0.5", 47000, tok, pin), JoinUri.parse("  cabincaption://join2?f=$pin&t=$tok&p=47000&h=192.168.0.5 "))
    }

    @Test
    fun hostPortIsForTheConsentScreenAndCarriesNoSecrets() {
        assertEquals("192.168.0.5:47000", JoinUri.parse(link("192.168.0.5"))!!.hostPort())
    }

    @Test
    fun rejectsWrongSchemeHostOrMissingParts() {
        listOf(
            null,
            "",
            "https://join2?h=10.0.0.1&p=1&t=$tok&f=$pin",
            "cabincaption://other?h=10.0.0.1&p=1&t=$tok&f=$pin",
            "cabincaption://join2",
            "cabincaption://join2?h=10.0.0.1&p=1&f=$pin",
            "cabincaption://join2?h=10.0.0.1&t=$tok&f=$pin",
            "cabincaption://join2?p=1&t=$tok&f=$pin",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&t=$tok&f=$pin",
            "cabincaption://join2?h=10.0.0.1&p=1&t&f=$pin",
        ).forEach { assertNull(it, JoinUri.parse(it)) }
    }

    @Test
    fun plaintextEraQrWithOldHostIsUnusable() {
        assertNull(JoinUri.parse("cabincaption://join?h=10.2.52.88&p=47100&t=$tok"))
        assertNull(JoinUri.parse("cabincaption://join?h=10.2.52.88&p=47100&t=$tok&f=$pin"))
    }

    @Test
    fun pinIsRequiredAndMustBe43Base64UrlChars() {
        listOf(
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&f=",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&f=${pin.drop(1)}",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&f=${pin}A",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&f=${pin.dropLast(1)}%2B",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&f=${pin.dropLast(1)}=",
            "cabincaption://join2?h=10.0.0.1&p=1&t=$tok&f=$pin&f=$pin",
        ).forEach { assertNull(it, JoinUri.parse(it)) }
    }

    @Test
    fun rejectsBadPortToken() {
        listOf(
            "h=10.0.0.1&p=0&t=$tok",
            "h=10.0.0.1&p=65536&t=$tok",
            "h=10.0.0.1&p=x&t=$tok",
            "h=10.0.0.1&p=1&t=short",
            "h=10.0.0.1&p=1&t=${tok}A",
            "h=10.0.0.1&p=1&t=AbCdEfGhIjKlMnOpQrSt%2B%2F",
        ).forEach { assertNull(it, JoinUri.parse("cabincaption://join2?$it&f=$pin")) }
        assertNull(JoinUri.parse(link("10.0.0.1") + "x".repeat(300)))
    }

    @Test
    fun acceptsRfc1918PrivateRangesAtTheirEdges() {
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.254", "192.168.43.1")
            .forEach { assertNotNull(it, JoinUri.parse(link(it))) }
    }

    @Test
    fun rejectsPublicAddressesSoAForgedQrCannotSendSpeechToTheInternet() {
        listOf("8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "192.167.0.1", "192.169.0.1", "11.0.0.1", "9.255.255.255")
            .forEach { assertNull(it, JoinUri.parse(link(it))) }
    }

    @Test
    fun rejectsCarrierGradeNat100_64BecauseItIsIspSharedSpaceNotACarOrHotspotLan() {
        listOf("100.64.0.1", "100.100.100.100", "100.127.255.254").forEach { assertNull(it, JoinUri.parse(link(it))) }
    }

    @Test
    fun rejectsLoopbackBecauseTheCarIsNeverOnThisPhone() {
        assertNull(JoinUri.parse(link("127.0.0.1")))
    }

    @Test
    fun rejectsLinkLocalMulticastAndMalformedHosts() {
        listOf("169.254.1.1", "0.1.2.3", "224.0.0.1", "255.255.255.255", "10.0.0.256", "example.com", "10.0.0", "10.0.0.1.2", "10..0.1")
            .forEach { assertNull(it, JoinUri.parse(link(it))) }
    }
}
