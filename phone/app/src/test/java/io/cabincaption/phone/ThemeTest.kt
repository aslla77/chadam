package io.cabincaption.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun ratio(a: Int, b: Int): Double {
        val la = Themes.luminance(a)
        val lb = Themes.luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun sixThemesInPickerOrderWithUniqueKeys() {
        assertEquals(listOf("dark", "light", "hyundai", "sand", "kia", "contrast"), ThemeId.entries.map { it.key })
    }

    @Test
    fun findsEachKeyAndRejectsUnknown() {
        ThemeId.entries.forEach { assertEquals(it, Themes.find(it.key)) }
        assertNull(Themes.find(null))
        assertNull(Themes.find(""))
        assertNull(Themes.find("Dark"))
        assertNull(Themes.find("blue"))
    }

    @Test
    fun missingOrUnknownSavedValueIsSystem() {
        assertEquals("system", Themes.choice(null))
        assertEquals("system", Themes.choice("gone"))
        assertEquals("system", Themes.choice("system"))
        assertEquals("kia", Themes.choice("kia"))
    }

    @Test
    fun systemFollowsDeviceMode() {
        assertEquals(ThemeId.DARK, Themes.resolve(null, true))
        assertEquals(ThemeId.LIGHT, Themes.resolve(null, false))
        assertEquals(ThemeId.DARK, Themes.resolve("system", true))
        assertEquals(ThemeId.LIGHT, Themes.resolve("system", false))
        assertEquals(ThemeId.LIGHT, Themes.resolve("gone", false))
    }

    @Test
    fun explicitChoiceIgnoresDeviceMode() {
        ThemeId.entries.forEach {
            assertEquals(it, Themes.resolve(it.key, true))
            assertEquals(it, Themes.resolve(it.key, false))
        }
    }

    @Test
    fun choiceKeysIncludeSystem() {
        assertTrue(Themes.isChoice("system"))
        assertTrue(Themes.isChoice("hyundai"))
        assertFalse(Themes.isChoice("blue"))
        assertFalse(Themes.isChoice(null))
    }

    @Test
    fun tokensMatchMockup() {
        val dark = Themes.palette(ThemeId.DARK)
        assertEquals(0xFF000000.toInt(), dark.bg)
        assertEquals(0xFFFFFFFF.toInt(), dark.accent)
        assertNull(dark.surfaceBorder)
        assertEquals(0xFF111111.toInt(), Themes.palette(ThemeId.LIGHT).accent)
        assertEquals(0xFF00AAD2.toInt(), Themes.palette(ThemeId.HYUNDAI).accent)
        assertEquals(0xFFA36B4F.toInt(), Themes.palette(ThemeId.SAND).shared)
        assertEquals(0xFF05141F.toInt(), Themes.palette(ThemeId.KIA).bg)
        val hc = Themes.palette(ThemeId.CONTRAST)
        assertEquals(0xFFFFFFFF.toInt(), hc.surfaceBorder)
        assertEquals(0xFFFFD54F.toInt(), hc.accent)
    }

    @Test
    fun textPairsMeetWcagAa() {
        ThemeId.entries.forEach { id ->
            val p = Themes.palette(id)
            listOf(
                "text/bg" to ratio(p.text, p.bg),
                "sub/bg" to ratio(p.sub, p.bg),
                "text/surface" to ratio(p.text, p.surface),
                "sub/surface" to ratio(p.sub, p.surface),
                "text/field" to ratio(p.text, p.field),
                "onAccent/accent" to ratio(p.onAccent, p.accent),
                "noticeText/notice" to ratio(p.noticeText, p.notice),
            ).forEach { (name, r) -> assertTrue("$id $name=$r", r >= 4.5) }
        }
    }

    @Test
    fun outlinesMeetNonTextContrast() {
        ThemeId.entries.forEach { id ->
            val p = Themes.palette(id)
            assertTrue("$id border", ratio(p.border, p.bg) >= 3.0)
            assertTrue("$id shared", ratio(p.shared, p.bg) >= 3.0)
        }
    }

    @Test
    fun lightBackgroundsOnly() {
        assertTrue(Themes.isLight(Themes.palette(ThemeId.LIGHT)))
        assertTrue(Themes.isLight(Themes.palette(ThemeId.SAND)))
        listOf(ThemeId.DARK, ThemeId.HYUNDAI, ThemeId.KIA, ThemeId.CONTRAST).forEach { assertFalse(Themes.isLight(Themes.palette(it))) }
    }
}
