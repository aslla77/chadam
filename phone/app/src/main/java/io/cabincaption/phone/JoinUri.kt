package io.cabincaption.phone

import io.cabincaption.protocol.Tls
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * 차 QR `cabincaption://join2?h=<사설 IPv4>&p=<포트>&t=<토큰>&f=<TLS 공개키 지문>`. 밖에서 들어오는 값이라 형식이 하나라도 틀리면 null.
 * [pin]은 연결·재접속 때마다 차 인증서와 맞춘다(ADR-0005).
 */
data class JoinUri(val host: String, val port: Int, val token: String, val pin: String) {

    /** 동의 화면 표시용. 토큰·지문은 넣지 않는다. */
    fun hostPort(): String = "$host:$port"

    companion object {
        private const val SCHEME = "cabincaption"
        private const val HOST = "join2"
        private val TOKEN = Regex("^[A-Za-z0-9_-]{22}$")

        fun parse(text: String?): JoinUri? {
            if (text == null || text.length > 256) return null
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || uri.host != HOST) return null
            val q = uri.rawQuery ?: return null
            val params = HashMap<String, String>()
            for (pair in q.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) return null
                val k = pair.substring(0, i)
                val v = try {
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                } catch (e: IllegalArgumentException) {
                    return null
                }
                if (params.put(k, v) != null) return null
            }
            val host = params["h"]?.takeIf(::privateIpv4) ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = params["t"]?.takeIf { TOKEN.matches(it) } ?: return null
            val pin = params["f"]?.takeIf(Tls::validPin) ?: return null
            return JoinUri(host, port, token, pin)
        }

        /** RFC 1918 사설 대역(10/8, 172.16/12, 192.168/16)만. 차와 폰은 같은 Wi-Fi·핫스팟에 있다. */
        private fun privateIpv4(h: String): Boolean {
            val parts = h.split('.')
            if (parts.size != 4) return false
            val o = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false else p.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
        }
    }
}
