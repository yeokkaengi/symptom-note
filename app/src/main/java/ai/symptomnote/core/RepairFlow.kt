package ai.symptomnote.core

import ai.symptomnote.data.Recording
import ai.symptomnote.data.Symptom

/**
 * 수리 완료 이후의 흐름. 안드로이드 의존 없음(단위 테스트 대상).
 * 앱 화면(수리 완료 버튼), 자동 포착 엔진, 증상 분석이 모두 이 함수를 쓴다.
 */
object RepairFlow {
    /**
     * 수리 완료를 기록한다. 자동 포착(autoCapture, rule)은 그대로 둔다:
     * 수리 후에도 같은 조건에서 소리가 다시 나는지 자동으로 잡아 "수리 후 녹음"으로 비교해야 하기 때문이다.
     */
    fun markRepaired(s: Symptom, at: Long): Symptom = s.copy(repairedAt = at)

    /** 자동 포착 엔진이 이 증상에 대해 지켜볼 규칙. 수리 여부와 상관없이 켜져 있으면 지켜본다. 빈 집합 = 지켜보지 않음. */
    fun watchRule(s: Symptom): Set<Cond> = if (s.autoCapture) Conditions.decode(s.rule) else emptySet()

    /** "수리 후 재생"에 쓸 녹음: 재생할 수 있는([playable]) 수리 후 기록 중 가장 최근 것. 없으면 null(버튼 비활성). */
    fun latestAfter(recs: List<Recording>, repairedAt: Long, playable: (Recording) -> Boolean): Recording? =
        recs.filter { it.isAfterRepair(repairedAt) && playable(it) }.maxByOrNull { it.t }
}
