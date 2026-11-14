package ai.symptomnote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {
    private val D = 8; private val R = 2; private val P = 4

    @Test fun 주행_기어에서_화면이_보이고_기록이_꺼져_있으면_자동_시작() {
        assertTrue(RecordingPolicy.shouldAutoStart(enabled = true, visible = true, gear = D, recording = false))
        assertTrue(RecordingPolicy.shouldAutoStart(true, true, R, false))
    }

    @Test fun 자동_시작하지_않는_경우() {
        assertFalse(RecordingPolicy.shouldAutoStart(enabled = false, visible = true, gear = D, recording = false)) // 설정 끔
        assertFalse(RecordingPolicy.shouldAutoStart(true, visible = false, gear = D, recording = false))         // 화면 안 보임(verify 5-8)
        assertFalse(RecordingPolicy.shouldAutoStart(true, true, gear = P, recording = false))                    // 주차
        assertFalse(RecordingPolicy.shouldAutoStart(true, true, gear = 0, recording = false))                    // 기어 아직 모름
        assertFalse(RecordingPolicy.shouldAutoStart(true, true, gear = D, recording = true))                     // 이미 켜짐
    }

    @Test fun 기록이_꺼져_저장_못_한_조건은_쿨다운_없이_다음에_다시_잡는다() {
        val j = AutoCaptureJudge()
        assertNotNull(j.onSample(0, true))     // 조건 시작 → 예약 시도
        j.onSaved(false)                        // 기록이 꺼져 있어 저장 못 함(AutoCaptureEngine 이 바로 onSaved(false))
        j.onSample(1_000, false)
        assertEquals(5_000L + AutoCaptureJudge.DELAY_MS, j.onSample(5_000, true)) // 기록을 켠 뒤 다음 시작 순간에 바로 저장(30초 안이어도)
    }
}
