package ai.symptomnote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointEstimateTest {
    private val rate = 100

    /** 초마다 진폭이 amp[i] 인 사각파 소리. RMS = amp[i]. */
    private fun pcm(vararg amp: Int) = ShortArray(amp.size * rate) { j -> (if (j % 2 == 0) amp[j / rate] else -amp[j / rate]).toShort() }

    @Test fun 초마다_RMS() {
        val r = PointEstimate.secondRms(pcm(100, 300, 50), rate)
        assertEquals(listOf(100.0, 300.0, 50.0), r.toList())
    }

    @Test fun 브레이크_구간_중_가장_큰_소리() {
        // 가장 큰 소리(1000)는 브레이크 밖, 브레이크 중에는 3번째 초(400)가 가장 큼
        val rms = doubleArrayOf(100.0, 1000.0, 200.0, 400.0, 300.0)
        val p = PointEstimate.estimate(rms, listOf(false, false, true, true, true))
        assertEquals(PointEstimate.Point(3, true), p)
    }

    @Test fun 브레이크가_없으면_전체에서_가장_큰_소리() {
        val rms = doubleArrayOf(100.0, 1000.0, 200.0)
        assertEquals(PointEstimate.Point(1, false), PointEstimate.estimate(rms, listOf(false, null, false)))
        assertNull(PointEstimate.estimate(DoubleArray(0), emptyList()))
    }

    @Test fun 대표_구간은_앞뒤_5초_가장자리에서는_10초_유지() {
        assertEquals(25 until 35, PointEstimate.clip(30, 60))
        assertEquals(0 until 10, PointEstimate.clip(2, 60))   // 앞이 모자람 → 뒤로 밂
        assertEquals(50 until 60, PointEstimate.clip(58, 60)) // 뒤가 모자람 → 앞으로 밂
        assertEquals(0 until 6, PointEstimate.clip(3, 6))     // 6초뿐이면 전체
    }

    @Test fun 조건_기록을_소리의_초에_맞춤() {
        fun s(t: Long, brake: Boolean) = DriveSample(1, t, 30f, brake, 0f, 8, 25f, false, false)
        // 소리 3초, 끝 10_000ms → 0번째 초 = (7000, 8000], 1번째 = (8000, 9000], 2번째 = (9000, 10000]
        val a = PointEstimate.align(listOf(s(8000, true), s(9500, false), s(10_000, true)), 10_000, 3)
        assertEquals(listOf(true, null, true), a.map { it?.brake })
        assertEquals(10_000L, a[2]?.t) // 같은 초에 둘이면 나중 것
        assertEquals(8000L, a[0]?.t)
        assertNull(PointEstimate.align(listOf(s(5000, true)), 10_000, 3).firstOrNull { it != null })
    }

    @Test fun 정차_30초_전_브레이크_끼익을_찾음() {
        // 60초: 20~25초 브레이크 + 끼익(큰 소리), 50~59초 정차하며 브레이크(조용함), 0~19초 브레이크 없이 큰 바람 소리
        val amp = IntArray(60) { i -> when (i) { in 0..19 -> 800; in 20..25 -> 600; else -> 100 } }
        amp[23] = 2000
        val brake = List(60) { it in 20..25 || it >= 50 }
        val p = PointEstimate.estimate(PointEstimate.secondRms(pcm(*amp), rate), brake)!!
        assertEquals(PointEstimate.Point(23, true), p)
        assertEquals(18 until 28, PointEstimate.clip(p.sec, 60))
    }
}
