package io.cabincaption.car

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchExtrasTest {
    @Test
    fun onlyDebuggableBuildsAcceptLaunchExtras() {
        assertTrue(LaunchExtras.allowed(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_HAS_CODE))
        assertFalse(LaunchExtras.allowed(ApplicationInfo.FLAG_HAS_CODE))
        assertFalse(LaunchExtras.allowed(0))
    }
}
