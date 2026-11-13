package io.cabincaption.car

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Secrets {
    /** 128비트 난수를 URL에 그대로 넣을 수 있는 base64url(패딩 없음, 22자)로. */
    fun newSecret(random: SecureRandom): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
