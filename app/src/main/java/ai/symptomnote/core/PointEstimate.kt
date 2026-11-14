package ai.symptomnote.core

import kotlin.math.sqrt

/**
 * 수동 기록(직전 60초)에서 "소리가 난 지점"을 추정한다. 안드로이드 의존 없음(단위 테스트 대상).
 *
 * 추정 방식(설계값, 실측으로 조정 필요):
 * 1. 60초 소리를 1초씩 나눠 각 초의 소리 크기(RMS)를 구한다.
 * 2. 같은 1초에 브레이크를 밟고 있던 초들 중 RMS 가 가장 큰 1초를 추정 지점으로 정한다
 *    (증상노트가 먼저 겨냥하는 증상이 브레이크 소음이기 때문).
 * 3. 브레이크를 밟은 초가 없으면 60초 전체에서 RMS 가 가장 큰 1초를 쓴다.
 * 4. 추정 지점 앞뒤 5초(모두 10초)를 대표 구간으로 잘라, 기존 10초 기록과 똑같이 공통 조건·리포트에 쓴다.
 */
object PointEstimate {
    /** 수동 기록이 보관하는 길이(초). 설계값. */
    const val MANUAL_SECONDS = 60
    /** 대표 구간: 추정 지점 앞 5초 ~ 뒤 5초. 설계값. */
    const val CLIP_BEFORE_SEC = 5
    const val CLIP_AFTER_SEC = 5
    /** 브레이크 구간을 먼저 본다. false 로 바꾸면 항상 전체에서 가장 큰 지점. 설계값. */
    const val PREFER_BRAKE = true

    /** sec = 소리 시작부터 몇 번째 초인가(0부터). byBrake = 브레이크 구간 안에서 찾았는가. */
    data class Point(val sec: Int, val byBrake: Boolean)

    /** 1초마다 RMS. 마지막의 1초가 안 되는 부분은 버린다. */
    fun secondRms(pcm: ShortArray, rate: Int): DoubleArray {
        val n = pcm.size / rate
        return DoubleArray(n) { i ->
            var sum = 0.0
            for (j in i * rate until (i + 1) * rate) { val v = pcm[j].toDouble(); sum += v * v }
            sqrt(sum / rate)
        }
    }

    /**
     * 소리의 각 초에 해당하는 1초 조건 기록을 맞춘다. 소리는 [endMs]에 끝났고 길이가 [seconds]초다.
     * i번째 초 = (endMs - (seconds - i)초, endMs - (seconds - i - 1)초]. 1초 요약의 t 는 "그 1초가 끝난 시각"이다.
     * 그 안에 기록이 없으면 null.
     */
    fun align(samples: List<DriveSample>, endMs: Long, seconds: Int): List<DriveSample?> =
        List(seconds) { i ->
            val from = endMs - (seconds - i) * 1000L
            samples.lastOrNull { it.t > from && it.t <= from + 1000 }
        }

    /** RMS 와 같은 길이의 브레이크 여부(null = 기록 없음)로 추정 지점을 고른다. 소리가 없으면 null. */
    fun estimate(rms: DoubleArray, brake: List<Boolean?>): Point? {
        if (rms.isEmpty()) return null
        val braking = rms.indices.filter { brake.getOrNull(it) == true }
        if (PREFER_BRAKE && braking.isNotEmpty()) return Point(braking.maxBy { rms[it] }, true)
        return Point(rms.indices.maxBy { rms[it] }, false)
    }

    /** 대표 구간 [시작 초, 끝 초). 앞뒤가 모자라면 반대쪽으로 밀어 가능한 한 10초를 유지한다. */
    fun clip(point: Int, total: Int): IntRange {
        val len = minOf(CLIP_BEFORE_SEC + CLIP_AFTER_SEC, total)
        val start = (point - CLIP_BEFORE_SEC).coerceIn(0, total - len)
        return start until start + len
    }
}
