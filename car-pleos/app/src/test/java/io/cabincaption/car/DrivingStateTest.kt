package io.cabincaption.car

import io.cabincaption.car.DrivingState.DRIVING
import io.cabincaption.car.DrivingState.PARKED
import io.cabincaption.car.DrivingState.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStateTest {

    @Test
    fun uxrMapsToDrivingStateFailClosed() {
        assertEquals(PARKED, DrivingState.fromUxr(false))
        assertEquals(DRIVING, DrivingState.fromUxr(true))
        assertEquals(UNKNOWN, DrivingState.fromUxr(null))
        assertFalse(PARKED.blocksNewJoin)
        assertTrue(DRIVING.blocksNewJoin)
        assertTrue(UNKNOWN.blocksNewJoin)
    }

    @Test
    fun onlyParkGearIsParked() {
        assertEquals(PARKED, DrivingState.fromGear("GEAR_PARK"))
        listOf("GEAR_DRIVE", "GEAR_REVERSE", "GEAR_NEUTRAL", "GEAR_1", "GEAR_9", "SOMETHING_NEW").forEach {
            assertEquals(it, DRIVING, DrivingState.fromGear(it))
        }
        assertEquals(UNKNOWN, DrivingState.fromGear(null))
        assertEquals(UNKNOWN, DrivingState.fromGear("GEAR_UNKNOWN"))
    }

    @Test
    fun combineIsParkedOnlyWhenBothParked() {
        val all = DrivingState.entries
        for (u in all) for (g in all) {
            val expected = when {
                u == DRIVING || g == DRIVING -> DRIVING
                u == PARKED && g == PARKED -> PARKED
                else -> UNKNOWN
            }
            assertEquals("uxr=$u gear=$g", expected, DrivingState.combine(u, g))
        }
    }

    @Test
    fun signalsPublishOnlyOnChange() {
        val seen = mutableListOf<Triple<DrivingState, DrivingState, DrivingState>>()
        val s = DrivingSignals { c, u, g -> seen += Triple(c, u, g) }
        s.uxr(PARKED)
        assertTrue(seen.isEmpty())
        s.gear(PARKED)
        s.gear(DRIVING)
        s.uxr(DRIVING)
        s.gear(PARKED)
        s.uxr(PARKED)
        assertEquals(
            listOf(
                Triple(PARKED, PARKED, PARKED),
                Triple(DRIVING, PARKED, DRIVING),
                Triple(PARKED, PARKED, PARKED),
            ),
            seen,
        )
    }

    @Test
    fun gearDriveBlocksEvenIfUxrSaysParked() {
        var last = UNKNOWN
        val s = DrivingSignals { c, _, _ -> last = c }
        s.uxr(PARKED)
        s.gear(PARKED)
        assertEquals(PARKED, last)
        s.gear(DRIVING)
        assertTrue(last.blocksNewJoin)
        s.gear(UNKNOWN)
        assertEquals(UNKNOWN, last)
        assertTrue(last.blocksNewJoin)
    }
}
