package io.cabincaption.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** 목록에서 빠진 설정이 바뀌면 Activity가 다시 만들어지고 onDestroy에서 세션이 끝난다(R3-M2). */
class ManifestConfigChangesTest {
    @Test
    fun activityHandlesEveryConfigChangeThatWouldEndTheSession() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val listed = Regex("android:configChanges=\"([^\"]+)\"").find(manifest)!!.groupValues[1].split("|").toSet()
        val required = setOf(
            "orientation", "screenSize", "screenLayout", "smallestScreenSize", "keyboardHidden", "uiMode",
            "fontScale", "density", "locale", "layoutDirection", "keyboard", "navigation", "fontWeightAdjustment",
        )
        assertEquals(required, listed)
    }
}
