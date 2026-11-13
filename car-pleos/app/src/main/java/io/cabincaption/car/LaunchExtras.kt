package io.cabincaption.car

import android.content.pm.ApplicationInfo

/** PT·점검용 실행 옵션(`qr_host`·`qr_port`·`audio_source`)은 디버그 빌드에서만 받는다. 같은 차의 다른 앱이 QR 주소를 바꾸지 못하게(M-3). */
object LaunchExtras {
    fun allowed(appFlags: Int): Boolean = appFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
