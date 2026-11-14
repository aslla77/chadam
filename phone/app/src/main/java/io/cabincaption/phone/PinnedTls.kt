package io.cabincaption.phone

import io.cabincaption.protocol.Tls
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** 차 연결을 TLS 1.3으로 감싼다. 차 인증서 첫 장의 공개키 지문이 QR `f`와 같을 때만 핸드셰이크가 끝난다(ADR-0005). */
object PinnedTls {

    /** [raw]는 이미 연결된 소켓. 지문이 다르거나 TLS 1.3이 아니면 IOException. 연결마다 새 컨텍스트라 세션 재개 없이 매번 지문을 본다. */
    fun wrap(raw: Socket, host: String, port: Int, pin: String): SSLSocket {
        val ctx = SSLContext.getInstance(Tls.PROTOCOL).apply { init(null, arrayOf(PinTrust(pin)), null) }
        return (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
            enabledProtocols = arrayOf(Tls.PROTOCOL)
            startHandshake()
        }
    }

    class PinTrust(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            if (!Tls.samePin(pin, Tls.pin(leaf.publicKey.encoded))) throw CertificateException("server key does not match QR")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
