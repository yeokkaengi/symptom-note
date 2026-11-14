package ai.symptomnote.demo

import ai.symptomnote.core.Cat
import ai.symptomnote.core.Cond
import ai.symptomnote.core.Conditions
import ai.symptomnote.core.SoundAnalysis
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 데이터가 의도한 이야기(차가운 날·브레이크 공통 조건, 수리 후 -20dB)를 실제로 만들어 내는지. */
class DemoPlanTest {
    private val now = 1_763_100_000_000L // 2025-11-14 기준 아무 시각
    private val plan = DemoPlan.make(now)

    @Test fun commonConditionsAreFound() {
        val before = plan.records.filter { it.inSymptom && it.t < plan.repairedAt }
        val base = Conditions.windows(plan.seconds.filter { it.t < plan.repairedAt })
        val a = Conditions.analyze(before.map { it.snap }, base)
        println("규칙=${Conditions.describe(a.rule)} · 평소=${a.ruleBase} · 창=${a.baselineWindows}")
        a.stats.forEach { println("  ${Conditions.label(it.cond)} ${it.hits}/${it.total} base=${it.base} adopted=${it.adopted}") }
        assertTrue(Cond(Cat.BRAKE, "ON") in a.rule)
        assertTrue(Cond(Cat.TEMP, "COLD") in a.rule)
        assertTrue(a.ruleBase!! in 0.01..0.2)
    }

    @Test fun passesAfterRepairAndQuieterSound() {
        val before = plan.records.filter { it.inSymptom && it.t < plan.repairedAt }
        val base = Conditions.windows(plan.seconds.filter { it.t < plan.repairedAt })
        val rule = Conditions.analyze(before.map { it.snap }, base).rule
        val passes = Conditions.countPasses(rule, plan.seconds.filter { it.t >= plan.repairedAt })
        println("수리 후 통과=$passes")
        assertTrue(passes >= 3)

        val b = SoundAnalysis.bands(DemoPlan.synth(before.last(), 48_000, 1), 48_000)
        val after = plan.records.first { it.auto }
        val c = SoundAnalysis.compare(b, SoundAnalysis.bands(DemoPlan.synth(after, 48_000, 2), 48_000))
        println("특징 대역=${SoundAnalysis.BAND_NAMES[c.band]} 변화=${c.deltaDb}")
        assertTrue(c.band == 1)
        assertTrue(c.deltaDb < -12)
    }
}
