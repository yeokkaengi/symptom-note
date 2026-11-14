package ai.symptomnote.ui

import ai.symptomnote.audio.Player
import ai.symptomnote.core.Conditions
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.Recording
import ai.symptomnote.data.Source
import ai.symptomnote.data.Symptom
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 스냅샷 한 줄 요약: "시속 22~35km · 브레이크 · 5°C · 기어 D" */
fun snapLine(r: Recording): String {
    val s = r.snap
    return listOfNotNull(
        "시속 ${"%.0f".format(s.speedMinKmh)}~${"%.0f".format(s.speedMaxKmh)}km",
        if (s.brake) "브레이크" else null,
        "핸들 ${"%.0f".format(s.steerMinDeg)}~${"%.0f".format(s.steerMaxDeg)}°",
        s.outsideTempC?.let { "${"%.0f".format(it)}°C" },
        if (s.wiperOn) "와이퍼" else null,
        if (s.turnOn) "방향지시등" else null,
        "기어 ${Conditions.gearName(s.gear)}"
    ).joinToString(" · ")
}

fun sourceLabel(r: Recording) = when (r.source) { Source.AUTO -> "자동"; Source.WINDOW -> "창문 점검"; else -> "수동" } + if (r.demo) "·시연" else ""

/** 재생 버튼(녹음 파일이 지워졌으면 비활성). */
@Composable
fun PlayButton(r: Recording?, label: String = "▶ 재생") {
    val playing by Player.playing.collectAsStateWithLifecycle()
    val ok = r?.file != null && File(r.file).exists()
    BigButton(if (ok && playing == r?.file) "■ 정지" else if (ok) label else "소리 없음", primary = false, enabled = ok) { Player.toggle(r?.file) }
}

@Composable
fun RecordRow(r: Recording, symptomName: String?, checked: Boolean?, onCheck: (Boolean) -> Unit = {}, actions: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (checked != null) Checkbox(checked, onCheck, Modifier.size(56.dp),
            colors = CheckboxDefaults.colors(checkedColor = C.Yellow, uncheckedColor = C.Sub, checkmarkColor = Color.Black))
        Column(Modifier.weight(1f).clickable(enabled = checked != null) { onCheck(checked != true) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(r.t), style = TextStyle(fontSize = 24.sp), color = C.Text)
                Text(sourceLabel(r), color = if (r.source == Source.AUTO) C.Blue else C.Yellow, style = TextStyle(fontSize = 22.sp))
                symptomName?.let { Text("[$it]", color = C.Green, style = TextStyle(fontSize = 22.sp)) }
            }
            r.pointAgoSec?.let { ago ->
                Text("추정 지점: 버튼 ${ago}초 전 (${if (r.pointByBrake == true) "브레이크 중 가장 큰 소리" else "1분 중 가장 큰 소리"}) · 앞뒤 5초 사용",
                    color = C.Yellow, style = TextStyle(fontSize = 22.sp))
            }
            Small(snapLine(r))
        }
        PlayButton(r)
        actions()
    }

/** 기록 목록: 고르고 → 증상으로 묶기. 녹음 삭제도 여기서. */
@Composable
fun RecordsScreen(back: () -> Unit, openSymptom: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val all by remember { dao.recordings() }.collectAsStateWithLifecycle(emptyList())
    val symptoms by remember { dao.symptoms() }.collectAsStateWithLifecycle(emptyList())
    val recs = all.filter { it.source != Source.WINDOW }
    val names = symptoms.associate { it.id to it.name }
    val sel = remember { mutableStateListOf<Long>() }
    var grouping by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("기록 목록", back) {
            BigButton("선택 ${sel.size}개 → 증상으로 묶기", enabled = sel.isNotEmpty()) { grouping = true }
        }
        if (all.any { it.demo }) DemoBanner()
        if (recs.isEmpty()) Body("아직 기록이 없어요. 홈에서 '기록 시작' 후, 소리가 나면 '방금 소리 기록'을 누르세요.", C.Sub)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recs, key = { it.id }) { r ->
                RecordRow(r, r.symptomId?.let { names[it] }, r.id in sel, { if (it) sel += r.id else sel -= r.id }) {
                    BigButton("삭제", primary = false) { deleting = r }
                }
            }
        }
    }

    if (grouping) GroupDialog(symptoms, onDismiss = { grouping = false }) { existing, newName ->
        val ids = sel.toList()
        scope.launch {
            val sid = withContext(Dispatchers.IO) {
                val id = existing ?: dao.insertSymptom(Symptom(name = newName.trim(), createdAt = System.currentTimeMillis()))
                dao.assign(ids, id); id
            }
            sel.clear(); grouping = false
            openSymptom(sid)
        }
    }
    deleting?.let { r ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("이 기록을 지울까요?", style = TextStyle(fontSize = 28.sp)) },
            text = { Body("녹음 파일과 그 순간의 차량 상태가 함께 지워져요. 되돌릴 수 없어요.") },
            confirmButton = { BigButton("지우기") {
                scope.launch {
                    withContext(Dispatchers.IO) { r.files().forEach { File(it).delete() }; dao.deleteRecording(r) }
                    if (Player.playing.value == r.file) Player.stop()
                    sel -= r.id; deleting = null
                }
            } },
            dismissButton = { BigButton("취소", primary = false) { deleting = null } })
    }
}

/** 증상 이름 붙이기: 새 증상(추천 이름 버튼 + 직접 입력) 또는 기존 증상에 추가. */
@Composable
private fun GroupDialog(symptoms: List<Symptom>, onDismiss: () -> Unit, onPick: (Long?, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("증상으로 묶기", style = TextStyle(fontSize = 30.sp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (symptoms.isNotEmpty()) {
                    Small("기존 증상에 추가")
                    symptoms.take(4).forEach { s -> BigButton(s.name, Modifier.fillMaxWidth(), primary = false) { onPick(s.id, s.name) } }
                }
                Small("새 증상 이름")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("브레이크 끼익", "덜컹거림", "윙 소리").forEach { BigButton(it, primary = false) { name = it } }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(fontSize = 24.sp, color = C.Text),
                    modifier = Modifier.fillMaxWidth(), placeholder = { Small("예: 브레이크 끼익") })
            }
        },
        confirmButton = { BigButton("새 증상 만들기", enabled = name.isNotBlank()) { onPick(null, name) } },
        dismissButton = { BigButton("취소", primary = false, onClick = onDismiss) })
}

/** 증상 목록. */
@Composable
fun SymptomsScreen(back: () -> Unit, open: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val symptoms by remember { dao.symptoms() }.collectAsStateWithLifecycle(emptyList())
    val recs by remember { dao.recordings() }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TopBar("증상", back)
        if (symptoms.any { it.demo }) DemoBanner()
        if (symptoms.isEmpty()) Body("아직 증상이 없어요. 기록 목록에서 기록을 골라 '증상으로 묶기'를 누르세요.", C.Sub)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(symptoms, key = { it.id }) { s ->
                val mine = recs.filter { it.symptomId == s.id }
                Row(Modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(14.dp)).clickable { open(s.id) }.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.demo) " (시연)" else "", style = TextStyle(fontSize = 28.sp), color = C.Text)
                        Small(buildString {
                            append("기록 ${mine.size}개 (자동 ${mine.count { it.source == Source.AUTO }}개)")
                            if (s.autoCapture) append(" · 자동 포착 켜짐")
                            s.repairedAt?.let { append(" · 수리 ${fmtDate(it)}") }
                        })
                    }
                    Text("열기 →", color = C.Yellow, style = TextStyle(fontSize = 24.sp))
                }
            }
        }
    }
}
