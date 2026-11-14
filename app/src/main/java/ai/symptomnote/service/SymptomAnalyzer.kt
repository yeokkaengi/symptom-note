package ai.symptomnote.service

import ai.symptomnote.audio.WavIo
import ai.symptomnote.core.Analysis
import ai.symptomnote.core.BandChange
import ai.symptomnote.core.Cond
import ai.symptomnote.core.Conditions
import ai.symptomnote.core.Snapshot
import ai.symptomnote.core.SoundAnalysis
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.Recording
import ai.symptomnote.data.Source
import ai.symptomnote.data.Symptom
import android.content.Context
import java.io.File

/** 수리 후 확인 결과. 모든 수치는 "참고 지표". */
data class RepairInfo(
    val repairedAt: Long,
    /** 수리 후 같은 조건(공통 조건)을 지나간 횟수(1초 요약 기준). */
    val passes: Int,
    val afterAuto: Int,
    val afterTotal: Int,
    val before: Recording?,
    /** 수리 전 특징 대역. */
    val band: Int?,
    val changes: List<Pair<Recording, BandChange>>
) {
    val avgDelta: Double? get() = changes.takeIf { it.isNotEmpty() }?.map { it.second.deltaDb }?.average()
    val recurrences get() = changes.filter { it.second.recurrence }

    fun summary(): String = buildString {
        append("수리 후 같은 조건 ${passes}회 통과 · 기록 ${afterTotal}개(자동 ${afterAuto}개)")
        val d = avgDelta
        if (band != null && d != null) append(" · 수리 전 특징 대역(${SoundAnalysis.BAND_NAMES[band]}Hz)이 평균 ${"%+.0f".format(d)}dB (참고 지표)")
        else if (before == null) append(" · 비교할 수리 전 녹음이 없음")
        else append(" · 비교할 수리 후 녹음이 아직 없음")
    }
}

data class SymptomInfo(
    val symptom: Symptom,
    val recs: List<Recording>,
    /** 공통 조건 계산에 쓰는 기록(수리 전). */
    val beforeRecs: List<Recording>,
    val analysis: Analysis,
    /** 자동 포착·수리 후 확인에 쓰는 규칙: 자동 포착을 켤 때 고정한 규칙, 없으면 지금 계산한 공통 조건. */
    val rule: Set<Cond>,
    val representative: Recording?,
    val repair: RepairInfo?
) {
    /** 화면 예: "차가운 아침, 시속 20~40km, 브레이크 중 — 4회 중 4회 (평소 주행에서는 이 조건이 8%)" */
    fun headline(): String {
        val a = analysis
        return when {
            a.total < Conditions.MIN_RECORDS -> "기록이 2개 이상이어야 공통 조건을 찾을 수 있어요 (지금 ${a.total}개)"
            !a.baselineEnough -> "평소 주행 기록이 부족해요 (${a.baselineWindows}초, ${Conditions.MIN_BASELINE_WINDOWS}초 이상 필요). 기록을 켜고 조금 더 달려 주세요"
            a.rule.isEmpty() -> "아직 뚜렷한 공통 조건이 없어요 (${a.total}개 기록)"
            else -> "${Conditions.describe(a.rule)} — ${a.total}회 중 ${a.ruleHits}회" +
                (a.ruleBase?.let { " (평소 주행에서는 이 조건이 ${Math.round(it * 100)}%)" } ?: "")
        }
    }

    val period: String get() = if (recs.isEmpty()) "-" else "${ai.symptomnote.ui.fmtTime(recs.first().t)} ~ ${ai.symptomnote.ui.fmtTime(recs.last().t)}"
}

/** 증상 화면·리포트·글레오가 함께 쓰는 계산. 작업 스레드에서 호출. */
object SymptomAnalyzer {
    private var baseCacheKey = -1
    private var baseCache: List<Snapshot> = emptyList()
    private val bandCache = HashMap<String, DoubleArray>()

    /** 평소 주행 10초 창(1초 요약 수가 바뀔 때만 다시 계산). 동시에 불려도 같은 결과를 두 번 계산할 뿐이다. */
    suspend fun baseline(c: Context): List<Snapshot> {
        val rows = AppDb.get(c).dao().allSeconds()
        if (rows.size != baseCacheKey) { baseCache = Conditions.windows(rows.map { it.s }); baseCacheKey = rows.size }
        return baseCache
    }

    @Synchronized
    fun bands(path: String): DoubleArray? = bandCache[path] ?: runCatching {
        val p = WavIo.read(File(path))
        SoundAnalysis.bands(p.samples, p.sampleRate)
    }.getOrNull()?.also { bandCache[path] = it }

    suspend fun load(c: Context, s: Symptom, recs: List<Recording>): SymptomInfo {
        val dao = AppDb.get(c).dao()
        val mine = recs.filter { it.source != Source.WINDOW }
        val beforeRecs = s.repairedAt?.let { r -> mine.filter { it.t < r } } ?: mine
        val analysis = Conditions.analyze(beforeRecs.map { it.snap }, baseline(c))
        val rule = Conditions.decode(s.rule).ifEmpty { analysis.rule }
        val withFile = beforeRecs.filter { it.file != null && File(it.file).exists() }
        val rep = withFile.firstOrNull { it.id == s.representativeId } ?: withFile.lastOrNull()
        val repair = s.repairedAt?.let { at ->
            val after = mine.filter { it.t >= at }
            val passes = Conditions.countPasses(rule, dao.secondsFrom(at).map { it.s })
            val beforeBands = rep?.file?.let { bands(it) }
            val changes = if (beforeBands == null) emptyList() else after.mapNotNull { r ->
                r.file?.let { bands(it) }?.let { r to SoundAnalysis.compare(beforeBands, it) }
            }
            RepairInfo(at, passes, after.count { it.source == Source.AUTO }, after.size, rep,
                beforeBands?.let { SoundAnalysis.strongest(it) }, changes)
        }
        return SymptomInfo(s, mine, beforeRecs, analysis, rule, rep, repair)
    }
}
