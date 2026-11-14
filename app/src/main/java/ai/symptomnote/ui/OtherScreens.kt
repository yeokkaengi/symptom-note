package ai.symptomnote.ui

import ai.symptomnote.audio.Player
import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.data.AppDb
import ai.symptomnote.demo.DemoData
import ai.symptomnote.vehicle.VehicleSignals
import ai.symptomnote.vehicle.WindowChecker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 정차 자가 점검 · 창문(실차 전제 기능). */
@Composable
fun WindowCheckScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val checker = remember { WindowChecker(ctx) }
    val steps by checker.steps.collectAsStateWithLifecycle()
    val checks by remember { AppDb.get(ctx).dao().windowChecks() }.collectAsStateWithLifecycle(emptyList())
    val running by RingRecorder.running.collectAsStateWithLifecycle()
    val live by VehicleSignals.live.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar("창문 점검", back)
        Panel {
            Title("실차 전제 기능")
            Body("운전석 창문을 중간까지 움직이며 나는 소리를 녹음하고, 첫 점검(기준)과 비교해요. " +
                "에뮬레이터에는 실제 창문이 없어 창문 소리가 녹음되지 않아요. 제어 뒤 값 변화도 시험 때마다 달랐어요" +
                "(verify 5-1: 값 그대로 0, 11-13 증상노트 시험: 목표 값 도달). " +
                "그래서 이 화면은 흐름만 보여 주며, 소리 비교는 실제 차량에서 의미가 있어요.", C.Sub)
            Body("동승석에 사람이 앉아 있으면 실행하지 않아요.", C.Yellow)
            Body("녹음 ${if (running) "켜짐" else "꺼짐(홈에서 기록 시작 필요)"} · 기어 ${if (live.parked) "P" else "P 아님"}")
            BigButton("점검 시작", enabled = running && live.parked && !checker.running) { confirm = true }
        }
        if (steps.isNotEmpty()) Panel { Title("진행"); steps.forEach { Body(it) } }
        if (checks.isNotEmpty()) Panel {
            Title("점검 녹음 (${checks.size})")
            checks.reversed().forEachIndexed { i, r ->
                RecordRow(r, if (i == checks.size - 1) "기준" else null, null)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("창문을 움직일까요?", style = TextStyle(fontSize = 28.sp)) },
        text = { Body("운전석 창문을 중간까지 내렸다가 원래 위치로 되돌려요. 창문 주변에 손이나 물건이 없는지 확인하세요.") },
        confirmButton = { BigButton("확인") { confirm = false; checker.run() } },
        dismissButton = { BigButton("취소", primary = false) { confirm = false } })
}

/** 설정: 시연 데이터, 녹음 파일 삭제, 신호 상태. */
@Composable
fun SettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val status by VehicleSignals.status.collectAsStateWithLifecycle()
    val seconds by remember { dao.secondCount() }.collectAsStateWithLifecycle(0)
    val demo by remember { dao.demoCount() }.collectAsStateWithLifecycle(0)
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun run(block: suspend () -> String) { busy = true; scope.launch { msg = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "실패: ${it.message}" } }; busy = false } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar("설정", back)
        msg?.let { Body(it, C.Green) }
        Panel {
            Title("시연 데이터")
            Body("지난 5일치 평소 주행과 '브레이크 끼익' 기록·수리 이력을 넣어, 공통 조건·리포트·수리 후 확인 화면을 바로 볼 수 있어요. 화면에 '시연'으로 표시돼요.", C.Sub)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton(if (busy) "처리 중…" else "시연 데이터 불러오기", enabled = !busy) { run { DemoData.load(ctx) } }
                BigButton("시연 데이터 지우기", primary = false, enabled = !busy && demo > 0) { run { DemoData.clear(ctx); "시연 데이터를 지웠어요" } }
            }
        }
        Panel {
            Title("녹음과 개인정보")
            Body("소리는 기기 안에만 저장되고, 직전 1분만 메모리에 둬요. 기록마다 '삭제'로 지울 수 있고, 아래 버튼으로 녹음 파일을 한 번에 지울 수 있어요(차량 상태 기록은 남음).", C.Sub)
            BigButton("녹음 파일 모두 삭제", primary = false) { confirmDelete = true }
        }
        Panel {
            Title("차량 신호 상태")
            Body("평소 주행 기록: ${seconds}초")
            status.toSortedMap().forEach { (k, v) -> Small("$k: $v") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("녹음 파일을 모두 지울까요?", style = TextStyle(fontSize = 28.sp)) },
        text = { Body("되돌릴 수 없어요. 기록의 차량 상태와 증상은 남아요.") },
        confirmButton = { BigButton("모두 삭제") {
            confirmDelete = false; Player.stop()
            run {
                val list = dao.withFiles()
                list.forEach { r -> r.files().forEach { File(it).delete() } }
                dao.clearFiles()
                "녹음 파일 ${list.size}개를 지웠어요"
            }
        } },
        dismissButton = { BigButton("취소", primary = false) { confirmDelete = false } })
}
