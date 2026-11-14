package io.cabincaption.car

import java.net.Inet4Address
import java.net.NetworkInterface

/** 차 서버가 bind할 주소(ADR-0005, threat-model T8). 모든 인터페이스(wildcard)에는 열지 않는다. */
object BindHost {
    const val LOOPBACK = "127.0.0.1"

    /** QR 주소가 이 기기의 주소면 그 주소에만, 아니면(에뮬: QR = Mac 주소, adb forward로 루프백에 들어옴, ADR-0004 T-10) 루프백에만. */
    fun choose(qrHost: String, localHosts: List<String>): String = if (qrHost in localHosts) qrHost else LOOPBACK

    /** 켜져 있는 루프백 밖 인터페이스의 IPv4 주소. */
    fun localIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) {
        emptyList()
    }
}
