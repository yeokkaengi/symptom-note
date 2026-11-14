package ai.symptomnote.core

/** 기록을 증상으로 묶을 때의 규칙. 안드로이드 의존 없음(단위 테스트 대상). */
object Grouping {
    const val MIX_ERROR = "시연 데이터와 실제 기록은 섞을 수 없어요"

    /**
     * 묶을 수 없으면 이유, 묶을 수 있으면 null.
     * [targetDemo] = 넣을 증상이 시연 데이터인가(새 증상이면 null), [recordDemo] = 고른 기록마다 시연 데이터인가.
     */
    fun mixError(targetDemo: Boolean?, recordDemo: List<Boolean>): String? =
        if ((recordDemo + listOfNotNull(targetDemo)).toSet().size > 1) MIX_ERROR else null

    /** 수리 날짜가 있는 증상에 넣을 때는 "수리 전 기록 / 수리 후 재발" 중 하나를 사용자에게 묻는다. */
    fun needsRepairChoice(repairedAt: Long?): Boolean = repairedAt != null

    /**
     * 수리 후 기록인가. 사용자가 고른 값([chosen])이 있으면 그것을, 없으면 기록 시각으로 판단한다.
     * 수리 날짜가 없으면 항상 false.
     */
    fun isAfterRepair(t: Long, repairedAt: Long?, chosen: Boolean?): Boolean =
        repairedAt != null && (chosen ?: (t >= repairedAt))
}
