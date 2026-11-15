package ai.symptomnote.core

import ai.symptomnote.data.Recording
import ai.symptomnote.data.Source
import ai.symptomnote.data.Symptom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 수리 완료 → 자동 포착 유지 → 같은 조건에서 자동 저장 → "수리 후 녹음"으로 분류 → "수리 후 재생" 활성.
 * 앱 코드와 같은 함수(RepairFlow, AutoCaptureJudge, Conditions, Recording.captured)를 그대로 거친다.
 */
class RepairFlowTest {
    private val repairedAt = 1_000_000_000L
    private val rule = setOf(Cond(Cat.BRAKE, "ON"), Cond(Cat.SPEED, "20"))
    private val symptom = Symptom(id = 7, name = "브레이크 끼익", createdAt = 0, autoCapture = true, rule = Conditions.encode(rule))

    private fun sample(kmh: Float, brake: Boolean) = DriveSample(1, 0, kmh, brake, 0f, 8, 25f, false, false)
    private val brakingSnap = Snapshot.of(sample(25f, true))
    private val cruisingSnap = Snapshot.of(sample(60f, false))

    private fun auto(t: Long, file: String = "auto_$t.wav") =
        Recording.captured(t, file, Source.AUTO, brakingSnap, 16_000, 1000, autoFor = symptom.id)

    private fun manualBefore(t: Long) =
        Recording(t = t, file = "manual_$t.wav", source = Source.MANUAL, symptomId = symptom.id, snap = brakingSnap)

    @Test fun markRepaired_keepsAutoCaptureOnAndRuleUnchanged() {
        val repaired = RepairFlow.markRepaired(symptom, repairedAt)
        assertEquals(repairedAt, repaired.repairedAt)
        assertTrue(repaired.autoCapture)
        assertEquals(symptom.rule, repaired.rule)
        assertEquals(rule, RepairFlow.watchRule(repaired))
    }

    @Test fun afterRepair_sameCondition_autoSavesAndCountsAsAfterRepair() {
        val repaired = RepairFlow.markRepaired(symptom, repairedAt)
        val watch = RepairFlow.watchRule(repaired)
        assertTrue("수리 후에도 자동 포착 엔진이 지켜봐야 함", watch.isNotEmpty())

        // 자동 포착 엔진과 같은 판단: 조건이 거짓 → 참이 되는 순간 + 3초에 저장 예약.
        val judge = AutoCaptureJudge()
        assertNull(judge.onSample(0, Conditions.matches(watch, Conditions.keys(cruisingSnap))))
        val saveAt = judge.onSample(1_000, Conditions.matches(watch, Conditions.keys(brakingSnap)))
        assertEquals(1_000 + AutoCaptureJudge.DELAY_MS, saveAt)
        judge.onSaved(true)

        // RecordSaver.save 와 같은 형태로 저장된 기록.
        val rec = auto(repairedAt + 60_000)
        assertEquals(symptom.id, rec.symptomId)
        assertNull(rec.afterRepair)
        assertTrue(rec.isAfterRepair(repaired.repairedAt))

        // "수리 후 재생" 버튼: latestAfter != null 이면 활성.
        val before = manualBefore(repairedAt - 86_400_000)
        val latest = RepairFlow.latestAfter(listOf(before, rec), repairedAt) { it.file != null }
        assertNotNull(latest)
        assertEquals(rec, latest)
    }

    @Test fun latestAfter_picksMostRecentAfterRepairRecording() {
        val a = auto(repairedAt + 10_000)
        val b = auto(repairedAt + 90_000)
        val c = auto(repairedAt + 50_000)
        val before = manualBefore(repairedAt - 1)
        assertEquals(b, RepairFlow.latestAfter(listOf(before, a, b, c), repairedAt) { true })
    }

    @Test fun latestAfter_noAfterRepairRecording_isNull_buttonDisabled() {
        val before = manualBefore(repairedAt - 1)
        assertNull(RepairFlow.latestAfter(listOf(before), repairedAt) { true })
        // 수리 후 기록이 있어도 녹음 파일이 지워졌으면 재생할 것이 없다.
        val deleted = auto(repairedAt + 10_000).copy(file = null)
        assertNull(RepairFlow.latestAfter(listOf(before, deleted), repairedAt) { it.file != null })
    }

    @Test fun watchRule_autoCaptureOff_watchesNothing() {
        val off = RepairFlow.markRepaired(symptom.copy(autoCapture = false), repairedAt)
        assertFalse(off.autoCapture)
        assertTrue(RepairFlow.watchRule(off).isEmpty())
    }
}
