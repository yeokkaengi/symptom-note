package ai.symptomnote.demo

import ai.symptomnote.core.DriveSample
import ai.symptomnote.core.Snapshot
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 시연 데이터 설계(안드로이드 의존 없음 → 단위 테스트로 결과 확인).
 * 이야기: 차가운 아침, 시속 20~40km 에서 브레이크를 밟으면 "끼익" 소리가 난다.
 * - 지난 5일: 하루 3번 주행(아침 1번은 4°C, 낮·저녁 2번은 14~16°C). 이것이 "평소 주행" 기준률이 된다.
 * - 수리 전 기록 4개(아침, 차가움, 브레이크, 시속 21~39km) + 묶지 않은 다른 기록 1개.
 * - 그저께 정오 수리 → 그 뒤 차가운 주행 3번 + 어제 아침 자동 포착 2개(소리가 약 -20dB 작아짐).
 */
object DemoPlan {
    data class Rec(val t: Long, val snap: Snapshot, val toneAmp: Double, val toneHz: Double, val auto: Boolean, val inSymptom: Boolean)

    class Plan(val seconds: List<DriveSample>, val records: List<Rec>, val repairedAt: Long)

    const val SQUEAL_HZ = 1600.0
    const val SQUEAL_AMP = 0.25
    const val AFTER_AMP = 0.025 // -20 dB

    private fun at(now: Long, daysAgo: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now; add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 20분 주행 하나. 목표 속도를 45~90초마다 바꾸고, 감속할 때 브레이크를 밟는다. */
    private fun trip(start: Long, minutes: Int, temp: Float, rnd: Random): List<DriveSample> {
        val out = ArrayList<DriveSample>()
        var v = 0f; var target = 30f; var nextChange = 0; var turnLeft = 0; var steer = 0f
        for (s in 0 until minutes * 60) {
            if (s >= nextChange) {
                target = listOf(0f, 30f, 50f, 50f, 70f, 70f).random(rnd)
                nextChange = s + 45 + rnd.nextInt(45)
            }
            val brake = v > target + 1
            v = if (brake) maxOf(target, v - 4f) else minOf(target, v + 1.5f)
            if (turnLeft == 0 && v < 30 && v > 5 && rnd.nextInt(40) == 0) turnLeft = 6
            steer = if (turnLeft > 0) { turnLeft--; 60f + rnd.nextFloat() * 50f } else (rnd.nextFloat() - 0.5f) * 8f
            out += DriveSample(start, start + s * 1000L, v, brake, steer, 8, temp, false, turnLeft > 0)
        }
        return out
    }

    fun make(now: Long): Plan {
        val rnd = Random(42)
        val seconds = ArrayList<DriveSample>()
        for (d in 5 downTo 1) {
            seconds += trip(at(now, d, 7, 30), 20, 4f, rnd)
            seconds += trip(at(now, d, 12, 30), 20, 16f, rnd)
            seconds += trip(at(now, d, 18, 30), 20, 14f, rnd)
        }
        // 그저께 정오 수리. 수리 뒤 차가운 주행: 그저께 밤, 어제 아침(위 반복에 포함), 어제 밤.
        val repairedAt = at(now, 2, 12, 0)
        seconds += trip(at(now, 2, 21, 0), 20, 5f, rnd)
        seconds += trip(at(now, 1, 21, 0), 20, 5f, rnd)
        val night = at(now, 1, 7, 30)

        fun snap(min: Float, max: Float, brake: Boolean, temp: Float, odo: Float) =
            Snapshot(min, max, (min + max) / 2, brake, -4f, 4f, 8, temp, false, false, odo)
        val records = listOf(
            Rec(at(now, 5, 7, 41), snap(24f, 37f, true, 3f, 12030.4f), SQUEAL_AMP, SQUEAL_HZ, false, true),
            Rec(at(now, 4, 7, 38), snap(21f, 33f, true, 5f, 12071.9f), SQUEAL_AMP, SQUEAL_HZ, false, true),
            Rec(at(now, 3, 7, 44), snap(26f, 39f, true, 4f, 12110.2f), SQUEAL_AMP * 0.9, SQUEAL_HZ, false, true),
            Rec(at(now, 2, 7, 36), snap(22f, 36f, true, 6f, 12152.8f), SQUEAL_AMP, SQUEAL_HZ, false, true),
            // 다른 소리(고속, 브레이크 없음): 증상에 묶지 않은 채 목록에만
            Rec(at(now, 3, 18, 47), Snapshot(62f, 68f, 65f, false, -2f, 3f, 8, 14f, false, false, 12121.5f), 0.0, 200.0, false, false),
            // 수리 후 자동 포착 2개: 같은 조건, 소리는 약 -20dB
            Rec(night + 6 * 60_000, snap(23f, 35f, true, 5f, 12190.1f), AFTER_AMP, SQUEAL_HZ, true, true),
            Rec(night + 13 * 60_000, snap(20f, 31f, true, 5f, 12193.6f), AFTER_AMP, SQUEAL_HZ, true, true)
        )
        return Plan(seconds, records, repairedAt)
    }

    /** 10초짜리 시연 소리: 도로 소음 + (있으면) 끼익 소리. 다른 소리는 낮은 웅웅거림. */
    fun synth(r: Rec, rate: Int, seed: Int): ShortArray {
        val rnd = Random(seed)
        return ShortArray(rate * 10) { i ->
            val t = i.toDouble() / rate
            var x = (rnd.nextDouble() - 0.5) * 0.08 + 0.05 * sin(2 * PI * 90 * t)
            if (r.toneAmp > 0) {
                // 브레이크를 밟는 6~9초 구간에서 끼익(약간 떨림)
                val env = if (t in 6.0..9.0) minOf(1.0, (t - 6.0) * 4, (9.0 - t) * 4) else 0.0
                x += r.toneAmp * env * sin(2 * PI * (r.toneHz * t + 3 * sin(2 * PI * 5 * t)))
            } else x += 0.15 * sin(2 * PI * r.toneHz * t) * (0.6 + 0.4 * sin(2 * PI * 0.5 * t))
            (x.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

}
