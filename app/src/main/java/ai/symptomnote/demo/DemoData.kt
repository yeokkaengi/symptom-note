package ai.symptomnote.demo

import ai.symptomnote.audio.WavIo
import ai.symptomnote.core.Conditions
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.DriveSecond
import ai.symptomnote.data.Recording
import ai.symptomnote.data.Source
import ai.symptomnote.data.Symptom
import ai.symptomnote.service.RecordSaver
import android.content.Context
import java.io.File

/** 설정 → "시연 데이터 불러오기". 모든 행에 demo=true 를 붙여 화면에 "시연" 표시, 한 번에 지울 수 있게 한다. */
object DemoData {
    private const val RATE = 48_000

    suspend fun load(c: Context): String {
        clear(c)
        val dao = AppDb.get(c).dao()
        val plan = DemoPlan.make(System.currentTimeMillis())
        dao.insertSeconds(plan.seconds.map { DriveSecond(s = it, demo = true) })

        val sid = dao.insertSymptom(Symptom(name = "브레이크 끼익", createdAt = plan.records.first().t, demo = true))
        val dir = RecordSaver.recDir(c).also { it.mkdirs() }
        var lastBefore = 0L
        plan.records.forEachIndexed { i, r ->
            val f = File(dir, "demo_$i.wav")
            val pcm = DemoPlan.synth(r, RATE, i + 1)
            WavIo.write(f, pcm, pcm.size, RATE)
            val id = dao.insertRecording(Recording(
                t = r.t, file = f.absolutePath, source = if (r.auto) Source.AUTO else Source.MANUAL,
                symptomId = if (r.inSymptom) sid else null, snap = r.snap, sampleRate = RATE,
                peak = WavIo.peak(pcm), autoForSymptomId = if (r.auto) sid else null, demo = true))
            if (r.inSymptom && !r.auto) lastBefore = id
        }
        // 자동 포착 규칙은 실제 계산 결과로 고정(수리 전 기록 + 평소 주행).
        val before = plan.records.filter { it.inSymptom && it.t < plan.repairedAt }
        val rule = Conditions.analyze(before.map { it.snap }, Conditions.windows(plan.seconds)).rule
        dao.updateSymptom(Symptom(id = sid, name = "브레이크 끼익", createdAt = plan.records.first().t, autoCapture = rule.isNotEmpty(),
            rule = Conditions.encode(rule).ifEmpty { null }, repairedAt = plan.repairedAt, representativeId = lastBefore, demo = true))
        return "시연 데이터를 불러왔어요: 평소 주행 ${plan.seconds.size / 60}분, 기록 ${plan.records.size}개, 증상 1개"
    }

    suspend fun clear(c: Context) {
        val dao = AppDb.get(c).dao()
        dao.demoRecordings().forEach { r -> r.file?.let { File(it).delete() } }
        dao.deleteDemoRecordings()
        dao.deleteDemoSymptoms()
        dao.deleteDemoSeconds()
    }
}
