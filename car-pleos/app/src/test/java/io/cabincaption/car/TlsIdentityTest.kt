package io.cabincaption.car

import io.cabincaption.protocol.Tls
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsIdentityTest {

    @Test
    fun certificateParsesBackAsSelfSignedV3WithP256Key() {
        val now = 1_763_200_000_000L
        val id = TlsIdentity.create(now)
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(id.certificate.encoded)) as X509Certificate
        assertEquals(3, cert.version)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals("1.2.840.10045.4.3.2", cert.sigAlgOID)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("CN=chadam", cert.subjectX500Principal.name)
        assertEquals(256, (cert.publicKey as ECPublicKey).params.curve.field.fieldSize)
        assertTrue(cert.serialNumber.signum() > 0)
        cert.verify(cert.publicKey)
        cert.checkValidity(java.util.Date(now))
        assertTrue(cert.notBefore.time < now && cert.notAfter.time > now)
    }

    @Test
    fun pinIsTheShaOfThisCertificatesPublicKey() {
        val id = TlsIdentity.create()
        assertEquals(Tls.pin(id.certificate.publicKey.encoded), id.pin)
        assertTrue(Tls.validPin(id.pin))
    }

    @Test
    fun everyIdentityHasItsOwnKey() {
        assertNotEquals(TlsIdentity.create().pin, TlsIdentity.create().pin)
    }

    @Test
    fun serverContextIsTls13() {
        assertEquals("TLSv1.3", TlsIdentity.create().serverContext().protocol)
    }

    @Test
    fun derLengthsAndOidsAreEncodedCanonically() {
        assertArrayEquals(byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02), Der.oid("1.2.840.10045.4.3.2"))
        assertArrayEquals(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), Der.oid("2.5.4.3"))
        assertArrayEquals(byteArrayOf(0x04, 0x7F), Der.tlv(0x04, ByteArray(0x7F)).copyOf(2))
        assertArrayEquals(byteArrayOf(0x04, 0x81.toByte(), 0x80.toByte()), Der.tlv(0x04, ByteArray(0x80)).copyOf(3))
        assertArrayEquals(byteArrayOf(0x04, 0x82.toByte(), 0x01, 0x00), Der.tlv(0x04, ByteArray(0x100)).copyOf(4))
        assertEquals(1 + 2 + 0x80, Der.tlv(0x04, ByteArray(0x80)).size)
    }
}
