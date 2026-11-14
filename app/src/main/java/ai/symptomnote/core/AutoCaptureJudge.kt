package ai.symptomnote.core

/**
 * 자동 포착 판단(증상 1개당 1개). 시간은 호출하는 쪽이 넣는다(테스트 가능하도록).
 *
 * - 조건이 거짓→참으로 바뀌는 "시작 순간"에만 저장을 예약한다. 참이 계속 이어져도 다시 저장하지 않는다
 *   (verify-app 5-13 메모: 조건이 30초 넘게 이어지면 재저장되던 문제를 여기서 막음).
 * - 저장 시각 = 시작 + [delayMs]. 링 버퍼가 10초이므로 조건 시작 7초 전 ~ 3초 후 소리가 들어간다.
 * - 같은 증상은 마지막 저장(예약) 시각부터 [cooldownMs] 안에는 다시 예약하지 않는다(verify-app 5-20).
 * - 저장이 실패하면 쿨다운을 걸지 않는다.
 */
class AutoCaptureJudge(
    val delayMs: Long = DELAY_MS,
    val cooldownMs: Long = COOLDOWN_MS
) {
    companion object {
        /** 조건 시작 후 저장까지 기다리는 시간. 설계값, 실측으로 조정 필요. */
        const val DELAY_MS = 3_000L
        /** 같은 증상 재저장 금지 시간. 설계값, 실측으로 조정 필요. */
        const val COOLDOWN_MS = 30_000L
    }

    private var prev = false
    private var lastSaveAt: Long? = null
    private var pendingAt: Long? = null

    /** 상태 1개를 넣는다. 새로 저장을 예약해야 하면 저장할 시각을, 아니면 null. */
    fun onSample(now: Long, matched: Boolean): Long? {
        val rising = matched && !prev
        prev = matched
        if (!rising || pendingAt != null) return null
        lastSaveAt?.let { if (now + delayMs - it < cooldownMs) return null }
        val at = now + delayMs
        pendingAt = at
        lastSaveAt = at
        return at
    }

    /** 예약한 저장이 끝났을 때 호출. ok=false 면 쿨다운을 풀어 다음 시작 순간에 다시 저장할 수 있게 한다. */
    fun onSaved(ok: Boolean) {
        pendingAt = null
        if (!ok) lastSaveAt = null
    }
}
