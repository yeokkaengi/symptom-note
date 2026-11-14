package ai.symptomnote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ConditionsTest {
    private fun snap(min: Float, max: Float, brake: Boolean, temp: Float? = 5f, steer: Float = 3f, gear: Int = 8) =
        Snapshot(min, max, (min + max) / 2, brake, -steer, steer, gear, temp, false, false, null)

    private fun sample(trip: Long, sec: Int, kmh: Float, brake: Boolean, temp: Float = 5f) =
        DriveSample(trip, sec * 1000L, kmh, brake, 0f, 8, temp, false, false)

    /** 평소 주행: 시속 60km, 브레이크 없음이 대부분이고 가끔 시속 25km 브레이크. */
    private fun baseline(): List<Snapshot> {
        val s = mutableListOf<DriveSample>()
        for (i in 0 until 600) {
            val slow = i % 100 in 0..7 // 100초 중 8초
            s += sample(1, i, if (slow) 25f else 60f, slow, temp = 15f)
        }
        return Conditions.windows(s)
    }

    @Test fun keys_speedBandsCoverMinToMax() {
        val k = Conditions.keys(snap(22f, 38f, true))
        assertTrue(Cond(Cat.SPEED, "20") in k)
        assertTrue(Cond(Cat.SPEED, "30") in k)
        assertFalse(Cond(Cat.SPEED, "40") in k)
        assertTrue(Cond(Cat.BRAKE, "ON") in k)
        assertTrue(Cond(Cat.TEMP, "COLD") in k)
        assertTrue(Cond(Cat.GEAR, "D") in k)
    }

    @Test fun matches_orWithinCategory_andAcross() {
        val rule = setOf(Cond(Cat.SPEED, "20"), Cond(Cat.SPEED, "30"), Cond(Cat.BRAKE, "ON"))
        assertTrue(Conditions.matches(rule, Conditions.keys(snap(31f, 31f, true))))
        assertFalse(Conditions.matches(rule, Conditions.keys(snap(31f, 31f, false))))
        assertFalse(Conditions.matches(rule, Conditions.keys(snap(55f, 55f, true))))
        assertFalse(Conditions.matches(emptySet(), Conditions.keys(snap(31f, 31f, true))))
    }

    @Test fun analyze_adoptsFrequentAndRareConditions() {
        val recs = listOf(snap(22f, 35f, true), snap(25f, 38f, true), snap(21f, 29f, true), snap(30f, 39f, true))
        val a = Conditions.analyze(recs, baseline())
        assertTrue(a.baselineEnough)
        // 브레이크: 4/4, 평소에는 드묾 → 채택
        assertTrue(Cond(Cat.BRAKE, "ON") in a.rule)
        // 차가운 날: 4/4, 평소(15도)에는 0% → 채택
        assertTrue(Cond(Cat.TEMP, "COLD") in a.rule)
        // 기어 D: 4/4 지만 평소에도 100% → 배율 1 → 채택 안 함
        assertFalse(Cond(Cat.GEAR, "D") in a.rule)
        // 직진: 4/4 지만 평소에도 100%
        assertFalse(Cond(Cat.STEER, "STRAIGHT") in a.rule)
        // 시속 20~30: 3/4=75%, 평소 드묾 → 채택(경계값 포함)
        assertTrue(Cond(Cat.SPEED, "20") in a.rule)
        assertEquals(4, a.ruleHits)
        assertNotNull(a.ruleBase)
        assertEquals(0.0, a.ruleBase!!, 1e-9) // 평소에는 차가운 날이 없었음
    }

    @Test fun analyze_belowInclusionThresholdNotAdopted() {
        // 브레이크 2/4 = 50% → 채택 안 함
        val recs = listOf(snap(22f, 25f, true), snap(22f, 25f, false), snap(22f, 25f, true), snap(22f, 25f, false))
        val a = Conditions.analyze(recs, baseline())
        assertFalse(Cond(Cat.BRAKE, "ON") in a.rule)
    }

    @Test fun analyze_needsTwoRecordsAndBaseline() {
        assertTrue(Conditions.analyze(listOf(snap(22f, 25f, true)), baseline()).rule.isEmpty())
        val noBase = Conditions.analyze(listOf(snap(22f, 25f, true), snap(22f, 25f, true)), emptyList())
        assertTrue(noBase.rule.isEmpty())
        assertFalse(noBase.baselineEnough)
        assertNull(noBase.stats.first().base)
    }

    @Test fun windows_slideWithinTripOnly() {
        val s = (0 until 15).map { sample(1, it, 30f, it == 0) } + (0 until 5).map { sample(2, it, 30f, false) }
        val w = Conditions.windows(s)
        assertEquals(20, w.size)
        // 트립 1: 0초의 브레이크는 0~9초 창까지 보이고 10초 창부터는 빠진다
        assertEquals(10, w.take(15).count { it.brake })
        assertEquals(0, w.drop(15).count { it.brake })
    }

    @Test fun countPasses_risingEdgesPerTrip() {
        val rule = setOf(Cond(Cat.BRAKE, "ON"))
        val s = listOf(false, true, true, false, true, false, false, true).mapIndexed { i, b -> sample(1, i, 30f, b) } +
            listOf(true, true).mapIndexed { i, b -> sample(2, i, 30f, b) }
        assertEquals(4, Conditions.countPasses(rule, s))
    }

    @Test fun describe_mergesSpeedBands() {
        val rule = setOf(Cond(Cat.SPEED, "20"), Cond(Cat.SPEED, "30"), Cond(Cat.BRAKE, "ON"), Cond(Cat.TEMP, "COLD"))
        assertEquals("차가운 날(바깥 10도 이하), 시속 20~40km, 브레이크 중", Conditions.describe(rule))
        assertEquals("시속 20~40km에서 브레이크, 바깥 온도 10도 이하일 때 들릴 가능성이 높음", Conditions.recipe(rule))
    }

    @Test fun encodeDecode_roundTrip() {
        val rule = setOf(Cond(Cat.SPEED, "20"), Cond(Cat.SPEED, "30"), Cond(Cat.BRAKE, "ON"))
        assertEquals(rule, Conditions.decode(Conditions.encode(rule)))
        assertTrue(Conditions.decode(null).isEmpty())
        assertTrue(Conditions.decode("NOPE=1").isEmpty())
    }
}

class AutoCaptureJudgeTest {
    @Test fun savesThreeSecondsAfterStart() {
        val j = AutoCaptureJudge()
        assertNull(j.onSample(0, false))
        assertEquals(4_000L, j.onSample(1_000, true))
    }

    @Test fun continuingConditionDoesNotSaveAgain() {
        val j = AutoCaptureJudge()
        assertEquals(3_000L, j.onSample(0, true)); j.onSaved(true)
        // 참이 60초 이어져도 다시 저장하지 않음
        for (t in 100L..60_000L step 100) assertNull(j.onSample(t, true))
    }

    @Test fun cooldownBlocksWithin30Seconds() {
        val j = AutoCaptureJudge()
        assertEquals(3_000L, j.onSample(0, true)); j.onSaved(true)
        j.onSample(5_000, false)
        assertNull(j.onSample(12_000, true)) // 12+3=15초, 마지막 저장 3초 → 12초 차이
        j.onSample(20_000, false)
        assertEquals(36_000L, j.onSample(33_000, true)) // 36-3=33초 ≥ 30초
    }

    @Test fun failedSaveClearsCooldown() {
        val j = AutoCaptureJudge()
        assertEquals(3_000L, j.onSample(0, true)); j.onSaved(false)
        j.onSample(4_000, false)
        assertEquals(8_000L, j.onSample(5_000, true))
    }

    @Test fun noSecondScheduleWhilePending() {
        val j = AutoCaptureJudge(cooldownMs = 0)
        assertEquals(3_000L, j.onSample(0, true))
        j.onSample(500, false)
        assertNull(j.onSample(1_000, true)) // 아직 저장 전
    }
}

class SoundAnalysisTest {
    private fun tone(rate: Int, sec: Double, vararg parts: Pair<Double, Double>): ShortArray =
        ShortArray((rate * sec).toInt()) { i -> (parts.sumOf { (f, a) -> a * sin(2 * PI * f * i / rate) } * 32767).toInt().toShort() }

    @Test fun bandEnergyMatchesTheory() {
        // 1.5kHz 진폭 0.5 → 평균제곱 0.125 = -9.03 dB, 5kHz 0.25 → 0.03125 = -15.05 dB
        val b = SoundAnalysis.bands(tone(48_000, 2.0, 1500.0 to 0.5, 5000.0 to 0.25), 48_000)
        assertEquals(-9.03, SoundAnalysis.db(b[1]), 0.2)
        assertEquals(-15.05, SoundAnalysis.db(b[3]), 0.2)
        assertEquals(1, SoundAnalysis.strongest(b))
    }

    @Test fun bandsAboveNyquistAreNaN() {
        val b = SoundAnalysis.bands(tone(16_000, 1.0, 1500.0 to 0.5), 16_000)
        assertTrue(b[4].isNaN())
        assertFalse(b[3].isNaN())
    }

    @Test fun compareReportsDropInCharacteristicBand() {
        val before = SoundAnalysis.bands(tone(48_000, 2.0, 1500.0 to 0.5, 300.0 to 0.1), 48_000)
        val after = SoundAnalysis.bands(tone(48_000, 2.0, 1500.0 to 0.05, 300.0 to 0.1), 48_000)
        val c = SoundAnalysis.compare(before, after)
        assertEquals(1, c.band)
        assertEquals(-20.0, c.deltaDb, 0.3)
        assertFalse(c.recurrence)
    }

    @Test fun similarAfterRepairIsRecurrence() {
        val before = SoundAnalysis.bands(tone(48_000, 2.0, 1500.0 to 0.5), 48_000)
        val after = SoundAnalysis.bands(tone(44_100, 2.0, 1500.0 to 0.4), 44_100)
        val c = SoundAnalysis.compare(before, after)
        assertEquals(1, c.band)
        assertTrue(c.recurrence) // 약 -1.9 dB
    }
}
