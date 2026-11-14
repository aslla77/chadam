package io.cabincaption.protocol

import java.security.MessageDigest
import java.util.Base64

/**
 * 차↔폰 전송은 TLS 1.3만 쓴다(ADR-0005). 차가 세션마다 키를 새로 만들고 QR `f`에 공개키 지문을 싣는다.
 * 지문 = SHA-256(SubjectPublicKeyInfo DER)의 base64url(패딩 없음, [PIN_CHARS]자). 폰은 서버 인증서 첫 장의 지문이 같을 때만 붙는다.
 */
object Tls {
    const val PROTOCOL = "TLSv1.3"
    const val PIN_CHARS = 43

    private val PIN = Regex("^[A-Za-z0-9_-]{$PIN_CHARS}$")

    fun pin(spki: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))

    fun validPin(s: String?): Boolean = s != null && PIN.matches(s)

    /** 상수시간 비교(길이가 다르면 바로 거짓). */
    fun samePin(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
}
