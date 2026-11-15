package ai.symptomnote.ui

import ai.symptomnote.Settings
import ai.symptomnote.audio.Player
import ai.symptomnote.data.AppDb
import ai.symptomnote.demo.DemoData
import ai.symptomnote.vehicle.VehicleSignals
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
    var autoStart by remember { mutableStateOf(Settings.autoStart(ctx)) }
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
            Title("주행 시작 시 자동으로 기록 켜기")
            Body("기어가 P에서 바뀌어 주행 기록 화면이 뜨면 기록을 자동으로 켜요. 끄면 홈의 '기록 시작'이나 주행 기록 화면에서 직접 켜야 해요.", C.Sub)
            BigButton(if (autoStart) "켜짐 (누르면 끄기)" else "꺼짐 (누르면 켜기)", primary = autoStart) {
                autoStart = !autoStart; Settings.setAutoStart(ctx, autoStart)
            }
        }
        Panel {
            Title("녹음과 개인정보")
            Body("주행을 시작하면 직전 1분 소리만 기기 안 메모리에 보관합니다. 버튼을 누르거나 자동 포착 조건일 때만 저장합니다.")
            Body("저장된 소리는 기기 안에만 있어요. 기록마다 '삭제'로 지울 수 있고, 아래 버튼으로 녹음 파일을 한 번에 지울 수 있어요(차량 상태 기록은 남음).", C.Sub)
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
