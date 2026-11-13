package io.cabincaption.car

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPriorityTest {

    @Test
    fun navigationGuidanceIsNav() {
        assertTrue(AudioPriority.isNav(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
        assertFalse(AudioPriority.isNav(AudioAttributes.USAGE_MEDIA))
        assertFalse(AudioPriority.isNav(AudioAttributes.USAGE_ASSISTANT))
    }

    // 오너 결정(2026-11-03): 전화 울림도 통화로 본다
    @Test
    fun voiceCallAndRingtoneAreCall() {
        assertTrue(AudioPriority.isCall(AudioAttributes.USAGE_VOICE_COMMUNICATION))
        assertTrue(AudioPriority.isCall(AudioAttributes.USAGE_NOTIFICATION_RINGTONE))
        assertFalse(AudioPriority.isCall(AudioAttributes.USAGE_MEDIA))
        assertFalse(AudioPriority.isCall(AudioAttributes.USAGE_NOTIFICATION))
        assertFalse(AudioPriority.isCall(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
    }

    @Test
    fun inCallCommunicationAndRingingModesAreCall() {
        assertTrue(AudioPriority.isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(AudioPriority.isCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(AudioPriority.isCallMode(AudioManager.MODE_RINGTONE))
        assertFalse(AudioPriority.isCallMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun noneNeverBlocks() {
        assertFalse(AudioPriority.NONE.call())
        assertFalse(AudioPriority.NONE.nav())
    }
}
