package io.cabincaption.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JoinUriTest {
    private val tok = "AbCdEfGhIjKlMnOpQrSt_-"

    @Test
    fun parsesCarQr() {
        assertEquals(JoinUri("10.2.52.88", 47100, tok), JoinUri.parse("cabincaption://join?h=10.2.52.88&p=47100&t=$tok"))
        assertEquals(JoinUri("192.168.0.5", 47000, tok), JoinUri.parse("  cabincaption://join?t=$tok&p=47000&h=192.168.0.5 "))
    }

    @Test
    fun rejectsWrongSchemeHostOrMissingParts() {
        listOf(
            null,
            "",
            "https://join?h=10.0.0.1&p=1&t=$tok",
            "cabincaption://other?h=10.0.0.1&p=1&t=$tok",
            "cabincaption://join",
            "cabincaption://join?h=10.0.0.1&p=1",
            "cabincaption://join?h=10.0.0.1&t=$tok",
            "cabincaption://join?p=1&t=$tok",
            "cabincaption://join?h=10.0.0.1&p=1&t=$tok&t=$tok",
            "cabincaption://join?h=10.0.0.1&p=1&t",
        ).forEach { assertNull(it, JoinUri.parse(it)) }
    }

    @Test
    fun rejectsBadHostPortToken() {
        listOf(
            "h=127.0.0.1&p=1&t=$tok",
            "h=0.1.2.3&p=1&t=$tok",
            "h=224.0.0.1&p=1&t=$tok",
            "h=10.0.0.256&p=1&t=$tok",
            "h=example.com&p=1&t=$tok",
            "h=10.0.0.1&p=0&t=$tok",
            "h=10.0.0.1&p=65536&t=$tok",
            "h=10.0.0.1&p=x&t=$tok",
            "h=10.0.0.1&p=1&t=short",
            "h=10.0.0.1&p=1&t=${tok}A",
            "h=10.0.0.1&p=1&t=AbCdEfGhIjKlMnOpQrSt%2B%2F",
        ).forEach { assertNull(it, JoinUri.parse("cabincaption://join?$it")) }
        assertNull(JoinUri.parse("cabincaption://join?h=10.0.0.1&p=1&t=$tok" + "x".repeat(300)))
    }
}
