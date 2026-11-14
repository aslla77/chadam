package io.cabincaption.car

import io.cabincaption.protocol.Tls
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 세션(서버 객체)마다 메모리에서 새로 만드는 TLS 신원(ADR-0005): EC P-256 키쌍 + 자체 서명 X.509 v3 인증서. 파일·KeyStore에 저장하지 않는다.
 * 폰은 인증서 체인이 아니라 QR의 [pin](공개키 지문)으로 차를 확인한다.
 */
class TlsIdentity private constructor(private val keyPair: KeyPair, val certificate: X509Certificate) {

    val pin: String = Tls.pin(certificate.publicKey.encoded)

    /** TLS 1.3 서버용. 메모리 KeyStore만 쓴다. */
    fun serverContext(): SSLContext {
        val pass = CharArray(0)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, pass, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, pass) }
        return SSLContext.getInstance(Tls.PROTOCOL).apply { init(kmf.keyManagers, null, null) }
    }

    companion object {
        private const val ALIAS = "car"
        private const val COMMON_NAME = "chadam"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
        private const val COMMON_NAME_OID = "2.5.4.3"

        fun create(now: Long = System.currentTimeMillis(), random: SecureRandom = SecureRandom()): TlsIdentity {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            return TlsIdentity(kp, selfSigned(kp, now, random))
        }

        // 폰은 유효기간을 보지 않지만(지문만 비교) 차 시계가 조금 틀려도 형식상 유효하게 앞뒤 하루를 둔다
        internal fun selfSigned(kp: KeyPair, now: Long, random: SecureRandom): X509Certificate {
            val serial = ByteArray(16).also { random.nextBytes(it) }
            serial[0] = ((serial[0].toInt() and 0x3F) or 0x40).toByte()
            val algorithm = Der.seq(Der.oid(ECDSA_WITH_SHA256))
            val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME_OID), Der.utf8(COMMON_NAME))))
            val tbs = Der.seq(
                Der.explicit(0, Der.integer(byteArrayOf(2))),
                Der.integer(serial),
                algorithm,
                name,
                Der.seq(Der.utcTime(now - DAY_MS), Der.utcTime(now + DAY_MS)),
                name,
                kp.public.encoded,
            )
            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initSign(kp.private, random)
                update(tbs)
            }.sign()
            val der = Der.seq(tbs, algorithm, Der.bitString(sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
    }
}

/** 인증서 하나를 만드는 데 필요한 만큼만 있는 DER 인코더. */
internal object Der {
    fun seq(vararg parts: ByteArray) = tlv(0x30, *parts)

    fun set(vararg parts: ByteArray) = tlv(0x31, *parts)

    fun explicit(tag: Int, inner: ByteArray) = tlv(0xA0 or tag, inner)

    /** [value]는 이미 최소 길이 2의 보수 big-endian이어야 한다. */
    fun integer(value: ByteArray) = tlv(0x02, value)

    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0), bytes)

    fun utcTime(ms: Long): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
    }

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            var v = arc
            val groups = ArrayDeque<Int>()
            do {
                groups.addFirst((v and 0x7F).toInt())
                v = v ushr 7
            } while (v > 0)
            groups.forEachIndexed { i, g -> out.write(if (i < groups.size - 1) g or 0x80 else g) }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArrayOutputStream()
        out.write(tag)
        if (n < 0x80) {
            out.write(n)
        } else {
            val len = generateSequence(n) { it ushr 8 }.takeWhile { it > 0 }.map { it and 0xFF }.toList().reversed()
            out.write(0x80 or len.size)
            len.forEach { out.write(it) }
        }
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
