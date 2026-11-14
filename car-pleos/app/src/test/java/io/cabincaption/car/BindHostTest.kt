package io.cabincaption.car

import org.junit.Assert.assertEquals
import org.junit.Test

class BindHostTest {

    @Test
    fun realCarBindsOnlyTheQrAddressWhenItIsThisDevice() {
        assertEquals("192.168.43.20", BindHost.choose("192.168.43.20", listOf("10.0.2.15", "192.168.43.20")))
    }

    @Test
    fun emulatorWithMacAddressInQrBindsLoopbackBecauseAdbForwardArrivesThere() {
        assertEquals("127.0.0.1", BindHost.choose("10.2.52.88", listOf("10.0.2.15")))
    }

    @Test
    fun noInterfacesFallsBackToLoopbackNotWildcard() {
        assertEquals("127.0.0.1", BindHost.choose("192.168.0.5", emptyList()))
    }

    @Test
    fun matchIsExactAddressNotPrefix() {
        assertEquals("127.0.0.1", BindHost.choose("192.168.0.1", listOf("192.168.0.10")))
    }
}
