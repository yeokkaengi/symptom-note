package ai.symptomnote.ui

import ai.symptomnote.core.CondStat
import ai.symptomnote.core.Conditions
import ai.symptomnote.core.SoundAnalysis
import ai.symptomnote.core.Snapshot
import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.vehicle.VehicleSignals
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.Source
import ai.symptomnote.service.SymptomAnalyzer
import ai.symptomnote.service.SymptomInfo
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 증상 + 기록이 바뀔 때마다 분석을 다시 계산한다. */
@Composable
fun rememberSymptomInfo(id: Long): SymptomInfo? {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val s by remember(id) { dao.symptom(id) }.collectAsStateWithLifecycle(null)
    val recs by remember(id) { dao.recordingsOf(id) }.collectAsStateWithLifecycle(emptyList())
    var info by remember(id) { mutableStateOf<SymptomInfo?>(null) }
    LaunchedEffect(s, recs) {
        val sym = s ?: return@LaunchedEffect
        info = withContext(Dispatchers.Default) { SymptomAnalyzer.load(ctx, sym, recs) }
    }
    return info
}

/** 조건 그래프: 기록 중 비율(노랑) vs 평소 주행 비율(파랑). */
@Composable
fun ConditionChart(stats: List<CondStat>, max: Int = 6) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    stats.take(max).forEach { st ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((if (st.adopted) "★ " else "") + Conditions.label(st.cond), color = if (st.adopted) C.Yellow else C.Text,
                style = TextStyle(fontSize = 22.sp), modifier = Modifier.width(320.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bar(st.inclusion, C.Yellow, Modifier.fillMaxWidth())
                Bar(st.base ?: 0.0, C.Blue, Modifier.fillMaxWidth())
            }
            Text("기록 ${st.hits}/${st.total} · 평소 ${st.base?.let { pct(it) } ?: "-"}", color = C.Sub,
                style = TextStyle(fontSize = 20.sp), modifier = Modifier.width(250.dp))
        }
    }
    Small("노랑: 이 증상 기록 중 그 조건이 있던 비율 · 파랑: 평소 주행에서 그 조건이 나온 비율 · ★ 공통 조건(기록 75% 이상, 평소의 2배 이상)")
}

@Composable
fun SymptomDetailScreen(id: Long, back: () -> Unit, openReport: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val info = rememberSymptomInfo(id)
    val scope = rememberCoroutineScope()
    var confirmRepair by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (info == null) { Body("불러오는 중…"); return }
    val s = info.symptom
    fun update(f: (ai.symptomnote.data.Symptom) -> ai.symptomnote.data.Symptom) = scope.launch(Dispatchers.IO) { dao.updateSymptom(f(s)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar(s.name, back) { BigButton("정비소 리포트", onClick = openReport) }
        if (s.demo) DemoBanner()
        Panel {
            Small("기록 ${info.recs.size}개 · ${info.period}")
            Title("공통 조건")
            Body(info.headline(), if (info.analysis.rule.isEmpty()) C.Sub else C.Yellow)
            if (info.analysis.rule.isNotEmpty()) Body("재현 레시피: ${Conditions.recipe(info.analysis.rule)}")
            if (info.analysis.stats.isNotEmpty()) ConditionChart(info.analysis.stats)
            Small("기준값(75%, 2배)은 설계값이며 실측으로 조정이 필요해요.")
        }
        Panel {
            Title("자동 포착")
            if (s.autoCapture) {
                Body("켜짐 · ${Conditions.describe(info.rule)}", C.Green)
                Body(Conditions.ruleSentence(info.rule))
                RuleStatus(info.rule)
                Small("조용히 저장해요(조건 7초 전 ~ 3초 후 소리). 30초 안에는 다시 저장하지 않아요.")
                BigButton("자동 포착 끄기", primary = false) { update { it.copy(autoCapture = false, rule = null) } }
            } else {
                Body(if (info.analysis.rule.isEmpty()) "공통 조건이 생기면 켤 수 있어요" else "규칙: ${Conditions.describe(info.analysis.rule)}", C.Sub)
                if (info.analysis.rule.isNotEmpty()) RuleStatus(info.analysis.rule)
                BigButton("자동 포착 켜기", enabled = info.analysis.rule.isNotEmpty()) {
                    update { it.copy(autoCapture = true, rule = Conditions.encode(info.analysis.rule)) }
                }
            }
        }
        RepairPanel(info, onRepair = { confirmRepair = true }, onClear = { update { it.copy(repairedAt = null) } })
        Panel {
            Title("기록 (${info.recs.size})")
            info.recs.reversed().forEach { r ->
                val after = r.isAfterRepair(s.repairedAt)
                RecordRow(r, if (after) "수리 후" else if (r.id == info.representative?.id) "대표" else null, null) {
                    if (!after) BigButton("대표로", primary = false, enabled = r.id != info.representative?.id) { update { it.copy(representativeId = r.id) } }
                    BigButton("빼기", primary = false) { scope.launch(Dispatchers.IO) { dao.assign(listOf(r.id), null); SymptomAnalyzer.refreshRule(ctx, s.id) } }
                }
            }
        }
        BigButton("이 증상 지우기(기록은 남김)", primary = false) { confirmDelete = true }
    }

    if (confirmRepair) AlertDialog(onDismissRequest = { confirmRepair = false },
        title = { Text("오늘 수리 완료로 기록할까요?", style = TextStyle(fontSize = 28.sp)) },
        text = { Body("이후 같은 조건을 지나간 횟수와 자동 포착 기록을 따로 세고, 수리 전 소리와 비교해요.") },
        confirmButton = { BigButton("수리 완료") { update { it.copy(repairedAt = System.currentTimeMillis()) }; confirmRepair = false } },
        dismissButton = { BigButton("취소", primary = false) { confirmRepair = false } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("'${s.name}' 증상을 지울까요?", style = TextStyle(fontSize = 28.sp)) },
        text = { Body("묶여 있던 기록은 기록 목록에 남아요.") },
        confirmButton = { BigButton("지우기") {
            scope.launch { withContext(Dispatchers.IO) { dao.unassignAll(s.id); dao.deleteSymptom(s) }; confirmDelete = false; back() }
        } },
        dismissButton = { BigButton("취소", primary = false) { confirmDelete = false } })
}

@Composable
private fun RepairPanel(info: SymptomInfo, onRepair: () -> Unit, onClear: () -> Unit) = Panel {
    Title("수리 후 확인")
    val rp = info.repair
    if (rp == null) {
        Body("수리를 받았다면 날짜를 기록하세요. 이후 같은 조건에서 소리가 다시 나는지 확인해 드려요.", C.Sub)
        BigButton("오늘 수리 완료로 기록", onClick = onRepair)
        return@Panel
    }
    Small("수리 완료: ${fmtDate(rp.repairedAt)}")
    Body(rp.summary(), C.Green)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayButton(rp.before, "▶ 수리 전")
        PlayButton(rp.changes.lastOrNull()?.first, "▶ 수리 후")
    }
    rp.changes.forEach { (r, c) ->
        Small("${fmtTime(r.t)} ${sourceLabel(r)} · ${SoundAnalysis.BAND_NAMES[c.band]}Hz ${"%+.1f".format(c.deltaDb)}dB" + if (c.recurrence) " · 재발 기록" else "")
    }
    if (rp.recurrences.isNotEmpty()) Body("재발 기록 ${rp.recurrences.size}개: 수리 후에도 특징 대역이 수리 전과 비슷해요(${SoundAnalysis.RECUR_DB.toInt()}dB보다 덜 줄어듦). 리포트에 포함됩니다.", C.Red)
    Small("dB 값은 참고 지표예요. 수리 여부를 판단하는 기준이 아닙니다.")
    BigButton("수리 기록 지우기", primary = false, onClick = onClear)
}

/** 정비소에 보여줄 한 장짜리 리포트. */
@Composable
fun ReportScreen(id: Long, back: () -> Unit) {
    val ctx = LocalContext.current
    val info = rememberSymptomInfo(id)
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<String?>(null) }
    if (info == null) { Body("불러오는 중…"); return }
    val a = info.analysis
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar("정비소 리포트", back) {
            BigButton("PDF로 저장") {
                scope.launch { saved = withContext(Dispatchers.IO) { runCatching { ReportPdf.write(ctx, info) }.fold({ "저장됨: $it" }, { "저장 실패: ${it.message}" }) } }
            }
        }
        saved?.let { Body(it, C.Green) }
        if (info.symptom.demo) DemoBanner()
        Panel {
            Text(info.symptom.name, style = TextStyle(fontSize = 40.sp), color = C.Yellow)
            Body("기록 ${info.recs.size}회 (자동 ${info.recs.count { it.source == Source.AUTO }}회) · ${info.period}")
            Title("공통 조건")
            Body(info.headline())
            Title("재현 레시피")
            Body(Conditions.recipe(info.rule), C.Yellow)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Title("대표 소리"); PlayButton(info.representative)
                info.representative?.let { Small("${fmtTime(it.t)} · ${snapLine(it)}") }
            }
            Title("조건 그래프")
            if (a.stats.isEmpty()) Body("기록이 없어요", C.Sub) else ConditionChart(a.stats)
            info.repair?.let { rp ->
                Title("수리 후 확인 (참고 지표)")
                Body("수리 완료 ${fmtDate(rp.repairedAt)} · ${rp.summary()}")
                if (rp.recurrences.isNotEmpty()) {
                    Body("재발 기록 ${rp.recurrences.size}개", C.Red)
                    rp.recurrences.forEach { (r, c) -> Small("${fmtTime(r.t)} · ${snapLine(r)} · ${"%+.1f".format(c.deltaDb)}dB") }
                }
            }
            Small("증상노트가 차량 신호와 녹음으로 정리한 자료입니다. 고장 원인을 판정하지 않습니다.")
        }
    }
}

/** 리포트를 A4 PDF 한 장으로(android.graphics.pdf, 추가 라이브러리 없음). 앱 전용 외부 폴더 reports/ 에 저장. */
object ReportPdf {
    fun write(c: Context, info: SymptomInfo): String {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val cv = page.canvas
        val title = Paint().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val head = Paint().apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val gray = Paint().apply { color = android.graphics.Color.rgb(200, 200, 200) }
        val yellow = Paint().apply { color = android.graphics.Color.rgb(230, 180, 0) }
        val blue = Paint().apply { color = android.graphics.Color.rgb(70, 130, 200) }
        var y = 50f
        fun text(s: String, p: Paint = body, gap: Float = 18f) {
            // 긴 줄은 폭에 맞춰 나눈다.
            var rest = s
            while (rest.isNotEmpty()) {
                var n = p.breakText(rest, true, 515f, null).coerceAtLeast(1)
                // 단어(띄어쓰기) 경계에서 끊어 숫자가 갈라지지 않게.
                if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > 0 }?.let { n = it + 1 }
                cv.drawText(rest.substring(0, n), 40f, y, p); y += gap; rest = rest.substring(n)
            }
        }
        text("증상노트 정비소 리포트", title, 30f)
        text("작성 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())}" + if (info.symptom.demo) " · 시연 데이터" else "")
        y += 6
        text(info.symptom.name, title, 28f)
        text("기록 ${info.recs.size}회 (자동 ${info.recs.count { it.source == Source.AUTO }}회) · ${info.period}")
        y += 6; text("공통 조건", head, 20f); text(info.headline())
        y += 6; text("재현 레시피", head, 20f); text(Conditions.recipe(info.rule))
        info.representative?.let { y += 6; text("대표 소리", head, 20f); text("${fmtTime(it.t)} · ${snapLine(it)} · 파일 ${File(it.file ?: "").name}") }
        y += 6; text("조건 그래프 (노랑: 증상 기록 중 비율, 파랑: 평소 주행 비율)", head, 22f)
        info.analysis.stats.take(8).forEach { st ->
            cv.drawText((if (st.adopted) "★ " else "") + Conditions.label(st.cond), 40f, y + 9, body)
            cv.drawRect(250f, y, 450f, y + 6, gray); cv.drawRect(250f, y, 250f + 200f * st.inclusion.toFloat(), y + 6, yellow)
            cv.drawRect(250f, y + 7, 450f, y + 13, gray); cv.drawRect(250f, y + 7, 250f + 200f * (st.base ?: 0.0).toFloat(), y + 13, blue)
            cv.drawText("${st.hits}/${st.total} · 평소 ${st.base?.let { pct(it) } ?: "-"}", 460f, y + 10, body)
            y += 20
        }
        info.repair?.let { rp ->
            y += 16; text("수리 후 확인 (참고 지표)", head, 20f)
            text("수리 완료 ${fmtDate(rp.repairedAt)} · ${rp.summary()}")
            if (rp.recurrences.isNotEmpty()) {
                text("재발 기록 ${rp.recurrences.size}개", head, 18f)
                rp.recurrences.forEach { (r, ch) -> text("· ${fmtTime(r.t)} · ${snapLine(r)} · ${"%+.1f".format(ch.deltaDb)}dB") }
            }
        }
        y += 10
        text("증상노트가 차량 신호와 녹음으로 정리한 자료입니다. 고장 원인을 판정하지 않습니다.", body.apply { color = android.graphics.Color.DKGRAY })
        doc.finishPage(page)
        val name = "report_${info.symptom.id}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        try {
            // API 29+: 공용 Download/SymptomNote 에 저장(권한 불필요). 사용자가 파일 앱·USB 로 꺼낼 수 있다.
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/SymptomNote")
                }
                val uri = c.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert 실패")
                c.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: error("파일을 열 수 없음")
                return "Download/SymptomNote/$name"
            }
            val dir = c.getExternalFilesDir("reports") ?: File(c.filesDir, "reports")
            dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { doc.writeTo(it) }
            return f.absolutePath
        } finally {
            doc.close()
        }
    }
}


/** 지금 차 상태가 자동 포착 규칙의 어느 조건을 만족하지 않는지 한 줄(시험·시연 때 왜 저장이 안 되는지 바로 보이게). */
@Composable
private fun RuleStatus(rule: Set<ai.symptomnote.core.Cond>) {
    val live by VehicleSignals.live.collectAsStateWithLifecycle()
    val running by RingRecorder.running.collectAsStateWithLifecycle()
    val line = Conditions.statusLine(rule, Conditions.keys(Snapshot.of(live.toSample(0, 0))), running)
    Body(line, if (line.startsWith("지금 차 상태")) C.Green else C.Yellow)
}
