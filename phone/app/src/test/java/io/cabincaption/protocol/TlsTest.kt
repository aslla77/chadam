package io.cabincaption.protocol

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsTest {

    private fun spki(): ByteArray =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

    @Test
    fun pinIsSha256OfSpkiAsUnpaddedBase64Url() {
        val pin = Tls.pin("abc".toByteArray())
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", pin)
        assertEquals(Tls.PIN_CHARS, pin.length)
        assertTrue(Tls.validPin(pin))
    }

    @Test
    fun differentKeysGiveDifferentPinsAndSameKeyTheSamePin() {
        val a = spki()
        val b = spki()
        assertEquals(Tls.pin(a), Tls.pin(a.copyOf()))
        assertNotEquals(Tls.pin(a), Tls.pin(b))
        assertTrue(Tls.validPin(Tls.pin(b)))
    }

    @Test
    fun validPinNeedsExactly43Base64UrlChars() {
        val ok = "A".repeat(43)
        assertTrue(Tls.validPin(ok))
        listOf(null, "", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "=", "A".repeat(42) + "+", "A".repeat(42) + "/", "A".repeat(42) + " ")
            .forEach { assertFalse("$it", Tls.validPin(it)) }
    }

    @Test
    fun samePinComparesWholeString() {
        val p = Tls.pin(spki())
        assertTrue(Tls.samePin(p, String(p.toCharArray())))
        assertFalse(Tls.samePin(p, p.dropLast(1) + if (p.last() == 'A') 'B' else 'A'))
        assertFalse(Tls.samePin(p, p.dropLast(1)))
    }

    @Test
    fun onlyTls13() {
        assertEquals("TLSv1.3", Tls.PROTOCOL)
    }
}
