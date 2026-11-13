package io.cabincaption.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 오너가 정한 문구. 뷰 코드는 단위 테스트가 없어 문구만 고정한다. */
class SilentSendStringsTest {
    private val xml = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String) = Regex("<string name=\"$name\">([^<]*)</string>").find(xml)!!.groupValues[1]

    @Test
    fun labelsAndStatusesAreAsApproved() {
        assertEquals("다른 폰으로", string("input_hint_silent"))
        assertEquals("차 스피커로", string("input_hint"))
        assertEquals("소리 없이 보냄", string("step_silent_done"))
        assertEquals("실패: 받을 폰 없음", string("fail_nobody"))
    }

    @Test
    fun consentStillStatesWhatTheCarMicDoes() {
        assertTrue(string("consent_mic").contains("폰 마이크 안 켬"))
        assertTrue(string("consent_storage").contains("저장 안 함"))
        assertTrue(string("consent_speaker").contains("차 스피커"))
        assertTrue(string("consent_accuracy").contains("긴급 안내용 아님"))
    }
}
