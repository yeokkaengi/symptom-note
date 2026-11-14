package ai.symptomnote.core

/** 기록(마이크 FGS) 자동 시작과 "기록 꺼짐" 안내 규칙. 안드로이드 의존 없음(단위 테스트 대상). */
object RecordingPolicy {
    const val GEAR_P = 4
    const val OFF_WARNING = "기록이 꺼져 있어요 · 자동 포착이 저장할 수 없어요"
    const val MISSED_CAPTURE = "자동 포착 조건 감지 · 기록이 꺼져 있어 저장 못 함"

    /**
     * 주행 기록 화면에서 기록을 자동으로 켤지.
     * 설정이 켜져 있고, 화면이 보이고(verify 5-8, 5-17: 화면 없이 시작한 마이크 FGS 는 막힘),
     * 기어가 주행 중(P 도 아니고 아직 모름(0)도 아님)이고, 기록이 아직 꺼져 있을 때만.
     */
    fun shouldAutoStart(enabled: Boolean, visible: Boolean, gear: Int, recording: Boolean): Boolean =
        enabled && visible && !recording && gear != 0 && gear != GEAR_P
}
