package io.cabincaption.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialPageTest {
    @Test
    fun pagesGoFromAboutToSafe() {
        assertEquals(
            listOf(TutorialPage.ABOUT, TutorialPage.JOIN, TutorialPage.CAPTION, TutorialPage.SPEAK, TutorialPage.SAFE),
            TutorialPage.entries.toList(),
        )
    }

    @Test
    fun nextAndPreviousStayInsideThePages() {
        assertEquals(1, TutorialPage.next(0))
        assertEquals(4, TutorialPage.next(4))
        assertEquals(0, TutorialPage.previous(0))
        assertEquals(3, TutorialPage.previous(4))
    }

    @Test
    fun onlyTheLastPageIsLast() {
        assertFalse(TutorialPage.isLast(0))
        assertFalse(TutorialPage.isLast(3))
        assertTrue(TutorialPage.isLast(4))
        assertTrue(TutorialPage.isLast(9))
    }

    @Test
    fun outOfRangeIndexFallsBackToTheNearestPage() {
        assertEquals(TutorialPage.ABOUT, TutorialPage.at(-3))
        assertEquals(TutorialPage.SAFE, TutorialPage.at(9))
    }
}
