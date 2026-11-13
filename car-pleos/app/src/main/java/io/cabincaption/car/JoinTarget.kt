package io.cabincaption.car

import java.net.URLEncoder

/** QR에 넣을 주소. IPv4만 받고, 폰이 닿을 수 없는 루프백·에뮬 내부(10.0.2.x)·링크 로컬과 선행 0(8진수로 읽힐 수 있음)은 거부한다(017 레드팀 C8). */
data class JoinTarget(val host: String, val port: Int) {

    sealed interface Input {
        data class Auto(val port: Int) : Input
        data class Valid(val target: JoinTarget) : Input
        data object BadHost : Input
        data object BadPort : Input
    }

    /** 화면 표시용. 토큰은 넣지 않는다. */
    fun hostPort(): String = "$host:$port"

    fun uri(token: String): String =
        "$SCHEME?h=$host&p=$port&t=${URLEncoder.encode(token, "UTF-8")}"

    companion object {
        const val SCHEME = "cabincaption://join"

        fun parse(host: String?, port: Int): JoinTarget? {
            if (host == null || port !in 1..65535 || !reachableIpv4(host)) return null
            return JoinTarget(host, port)
        }

        /** 시연 설정 입력 검증. 주소 공백=자동, 포트 공백=기본 포트. 주소 규칙은 [parse]와 같다. */
        fun fromInput(hostText: String, portText: String): Input {
            val port = portText.trim().ifEmpty { SessionController.LISTEN_PORT.toString() }.toIntOrNull()
            if (port == null || port !in 1..65535) return Input.BadPort
            val host = hostText.trim()
            if (host.isEmpty()) return Input.Auto(port)
            return parse(host, port)?.let { Input.Valid(it) } ?: Input.BadHost
        }

        /** 설정값이 있으면 그것만 쓰고(틀리면 null), 없으면 후보 주소 중 첫 번째 쓸 수 있는 것. */
        fun choose(configuredHost: String?, candidates: List<String>, port: Int): JoinTarget? =
            if (!configuredHost.isNullOrEmpty()) parse(configuredHost, port)
            else candidates.firstNotNullOfOrNull { parse(it, port) }

        private fun reachableIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val o = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return false else p.toInt() }
            if (o.any { it > 255 }) return false
            return when {
                o[0] == 0 || o[0] == 127 || o[0] >= 224 -> false
                o[0] == 169 && o[1] == 254 -> false
                o[0] == 10 && o[1] == 0 && o[2] == 2 -> false
                else -> true
            }
        }
    }
}
