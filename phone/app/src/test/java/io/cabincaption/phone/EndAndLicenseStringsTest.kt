package io.cabincaption.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 뷰 코드는 단위 테스트가 없어 문구만 고정한다. */
class EndAndLicenseStringsTest {
    private val xml = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String) = Regex("<string name=\"$name\"[^>]*>([^<]*)</string>").find(xml)!!.groupValues[1]

    @Test
    fun kickedEndIsAShortWordLikeTheOtherEnds() {
        assertEquals("내보내짐", string("kicked"))
    }

    // 폰 release APK의 런타임 의존성은 ZXing core·Kotlin stdlib·JetBrains annotations(security/dependency-review.md)
    @Test
    fun licensesNameEveryBundledLibrary() {
        assertTrue(string("license_zxing").contains("ZXing core"))
        assertTrue(string("license_kotlin").contains("Kotlin stdlib"))
        assertTrue(string("license_kotlin").contains("JetBrains annotations"))
        assertTrue(string("license_kotlin_sub").contains("Apache License 2.0"))
        assertTrue(string("license_noto_sub").contains("SIL Open Font License 1.1"))
    }
}
