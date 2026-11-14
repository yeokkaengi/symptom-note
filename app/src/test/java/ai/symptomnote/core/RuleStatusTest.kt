package ai.symptomnote.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleStatusTest {
    private val coldBrake = Conditions.decode("BRAKE=ON|SPEED=20,30|TEMP=COLD")
    private fun now(speed: Float, brake: Boolean, temp: Float?) =
        Conditions.keys(Snapshot.of(DriveSample(0, 0, speed, brake, 0f, 8, temp, false, false)))

    @Test fun 에뮬레이터_25도에서는_온도_조건이_안_맞음() {
        assertEquals(listOf("바깥 온도가 10도 이하가 아님"), Conditions.unmet(coldBrake, now(30f, true, 25f)))
    }

    @Test fun 안_맞는_조건을_모두_나열() {
        assertEquals(listOf("바깥 온도가 10도 이하가 아님", "속도가 시속 20~40km가 아님", "브레이크를 밟고 있지 않음"),
            Conditions.unmet(coldBrake, now(60f, false, 25f)))
        assertEquals(listOf("바깥 온도를 알 수 없음"), Conditions.unmet(coldBrake, now(25f, true, null)))
        assertEquals(emptyList<String>(), Conditions.unmet(coldBrake, now(25f, true, 5f)))
    }

    @Test fun 한_줄_상태() {
        assertEquals("지금 저장 안 됨: 녹음이 꺼져 있음 · 바깥 온도가 10도 이하가 아님",
            Conditions.statusLine(coldBrake, now(30f, true, 25f), recording = false))
        assertEquals("지금 차 상태: 규칙을 모두 만족해요", Conditions.statusLine(coldBrake, now(30f, true, 5f), recording = true))
        assertEquals("녹음 중에 \"차가운 날(바깥 10도 이하), 시속 20~40km, 브레이크 중\" 조건이 모두 맞기 시작하면 3초 뒤 저장해요",
            Conditions.ruleSentence(coldBrake))
    }
}
