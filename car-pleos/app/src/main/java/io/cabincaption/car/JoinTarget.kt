package io.cabincaption.car

import java.net.URLEncoder

/**
 * QR에 넣을 주소. 폰 `JoinUri`와 같은 RFC 1918 사설 IPv4(10/8, 172.16/12, 192.168/16)만 받는다(T7).
 * 그 안에서도 에뮬 내부(10.0.2.x)와 선행 0(8진수로 읽힐 수 있음)은 거부한다(017 레드팀 C8).
 */
data class JoinTarget(val host: String, val port: Int) {

    sealed interface Input {
        data class Auto(val port: Int) : Input
        data class Valid(val target: JoinTarget) : Input
        data object BadHost : Input
        data object BadPort : Input
    }

    /** 화면 표시용. 토큰은 넣지 않는다. */
    fun hostPort(): String = "$host:$port"

    /** [pin] = 이 세션 TLS 공개키 지문(ADR-0005). 폰은 `f`가 없거나 틀리면 붙지 않는다. */
    fun uri(token: String, pin: String): String =
        "$SCHEME?h=$host&p=$port&t=${URLEncoder.encode(token, "UTF-8")}&f=${URLEncoder.encode(pin, "UTF-8")}"

    companion object {
        // 평문 시절 폰(host "join")이 TLS 차의 QR을 받아 토큰을 평문으로 보내지 않도록 host를 바꿨다(ADR-0005)
        const val SCHEME = "cabincaption://join2"

        fun parse(host: String?, port: Int): JoinTarget? {
            if (host == null || port !in 1..65535 || !privateIpv4(host)) return null
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

        private fun privateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val o = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return false else p.toInt() }
            if (o.any { it > 255 }) return false
            return when {
                o[0] == 10 -> !(o[1] == 0 && o[2] == 2)
                o[0] == 172 -> o[1] in 16..31
                o[0] == 192 -> o[1] == 168
                else -> false
            }
        }
    }
}
