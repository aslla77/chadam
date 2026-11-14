package io.cabincaption.car

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 뷰 코드는 단위 테스트가 없어 문구와 고지 파일만 고정한다. */
class CarScreenStringsTest {
    private val xml = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String) = Regex("<string name=\"$name\"[^>]*>([^<]*)</string>").find(xml)!!.groupValues[1]

    @Test
    fun kickLabelIsShort() {
        assertEquals("내보내기", string("kick"))
        assertEquals("%1\$s 내보내기", string("kick_description"))
    }

    // 차 release APK에 들어가는 오픈소스(security/dependency-review.md)와 Apache-2.0 원문(§4(a) 사본 제공)
    @Test
    fun licensesNameBundledLibrariesAndCarryTheApacheText() {
        listOf(
            "license_zxing" to "ZXing core",
            "license_androidx" to "AndroidX",
            "license_dagger" to "Hilt",
            "license_gson" to "Gson",
            "license_kotlin" to "Kotlin stdlib",
            "license_coroutines" to "kotlinx-coroutines",
            "license_misc" to "javax.inject",
            "license_noto_sub" to "SIL Open Font License 1.1",
        ).forEach { (name, part) -> assertTrue(name, string(name).contains(part)) }
        assertEquals("Pleos Playground 제공", string("license_pleos_sub"))
        val apache = File("src/main/res/raw/apache_license_2_0.txt").readText()
        assertTrue(apache.contains("Apache License"))
        assertTrue(apache.contains("Version 2.0, January 2004"))
        assertTrue(apache.contains("END OF TERMS AND CONDITIONS"))
    }
}
