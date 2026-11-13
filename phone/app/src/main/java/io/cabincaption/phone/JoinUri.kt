package io.cabincaption.phone

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** 차 QR `cabincaption://join?h=<IPv4>&p=<포트>&t=<토큰>`. 밖에서 들어오는 값이라 형식이 하나라도 틀리면 null. */
data class JoinUri(val host: String, val port: Int, val token: String) {

    companion object {
        private const val SCHEME = "cabincaption"
        private const val HOST = "join"
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
            val host = params["h"]?.takeIf(::ipv4) ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val token = params["t"]?.takeIf { TOKEN.matches(it) } ?: return null
            return JoinUri(host, port, token)
        }

        private fun ipv4(h: String): Boolean {
            val parts = h.split('.')
            if (parts.size != 4) return false
            val o = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return false else p.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] != 0 && o[0] != 127 && o[0] < 224
        }
    }
}
