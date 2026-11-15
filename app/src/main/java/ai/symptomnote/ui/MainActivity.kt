package ai.symptomnote.ui

import ai.symptomnote.audio.Player
import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.core.Conditions
import ai.symptomnote.data.AppDb
import ai.symptomnote.service.AutoCaptureEngine
import ai.symptomnote.service.RecordingService
import ai.symptomnote.vehicle.Live
import ai.symptomnote.vehicle.UxWatcher
import ai.symptomnote.vehicle.VehicleSignals
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 주차 중 화면 이동. */
sealed interface Screen {
    data object Home : Screen
    data object Records : Screen
    data object Symptoms : Screen
    data class SymptomDetail(val id: Long) : Screen
    data class Report(val id: Long) : Screen
    data object Settings : Screen
}

/**
 * 주차 중 화면(DO 선언 없음). 주행 중에는 시스템이 이 화면을 가리고(verify 5-9),
 * 앱도 CarUxRestrictions 를 보고 "주행 중 기록 화면" 외의 기능을 열지 않는다.
 */
class MainActivity : ComponentActivity() {
    private lateinit var ux: UxWatcher

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[PERM_SPEED] == true) VehicleSignals.restartCar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ux = UxWatcher(this)
        val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, PERM_SPEED)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
        setContent { NoteTheme { Root() } }
    }

    override fun onDestroy() {
        ux.release()
        Player.stop()
        super.onDestroy()
    }

    fun openDrivingScreen() =
        startActivity(Intent(this, DrivingRecordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** 화면이 실제로 보일 때만 녹음을 시작한다(verify 5-8: 화면 없이 시작한 마이크 FGS 는 막힘). */
    fun startRecording(): String? {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return "화면이 보일 때만 시작할 수 있어요"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            return "마이크 권한을 허용해 주세요"
        }
        RecordingService.start(this)
        return null
    }

    @Composable
    private fun Root() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        val restricted by ux.restricted.collectAsStateWithLifecycle()
        // 주행이 시작되면 시스템이 이 화면 전체를 "주행 중에는 사용할 수 없습니다"로 가리므로 큰 버튼 화면(DO)을 바로 띄운다.
        LaunchedEffect(restricted) { if (restricted) openDrivingScreen() }
        BackHandler(enabled = screen != Screen.Home) {
            screen = when (val s = screen) { is Screen.Report -> Screen.SymptomDetail(s.id); is Screen.SymptomDetail -> Screen.Symptoms; else -> Screen.Home }
        }
        Box(Modifier.fillMaxSize().background(C.Bg).padding(24.dp)) {
            if (restricted) DrivingNotice(::openDrivingScreen)
            else when (val s = screen) {
                Screen.Home -> HomeScreen(go = { screen = it })
                Screen.Records -> RecordsScreen(back = { screen = Screen.Home }, openSymptom = { screen = Screen.SymptomDetail(it) })
                Screen.Symptoms -> SymptomsScreen(back = { screen = Screen.Home }, open = { screen = Screen.SymptomDetail(it) })
                is Screen.SymptomDetail -> SymptomDetailScreen(s.id, back = { screen = Screen.Symptoms }, openReport = { screen = Screen.Report(s.id) })
                is Screen.Report -> ReportScreen(s.id, back = { screen = Screen.SymptomDetail(s.id) })
                Screen.Settings -> SettingsScreen(back = { screen = Screen.Home })
            }
        }
    }

    @Composable
    private fun DrivingNotice(open: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Title("주행 중에는 '방금 소리 기록' 화면만 쓸 수 있어요")
        Body("기록 목록·증상·리포트는 차를 세우고 기어를 P로 바꾸면 다시 열립니다.", C.Sub)
        BigButton("주행 중 기록 화면 열기", Modifier.fillMaxWidth(), onClick = open)
    }

    @Composable
    private fun HomeScreen(go: (Screen) -> Unit) {
        val live by VehicleSignals.live.collectAsStateWithLifecycle()
        val running by RingRecorder.running.collectAsStateWithLifecycle()
        val level by RingRecorder.level.collectAsStateWithLifecycle()
        val err by RecordingService.error.collectAsStateWithLifecycle()
        val auto by AutoCaptureEngine.last.collectAsStateWithLifecycle()
        val demo by remember { AppDb.get(this).dao().demoCount() }.collectAsStateWithLifecycle(0)
        var msg by remember { mutableStateOf<String?>(null) }
        val canStart = live.gear == 0 || live.parked

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TopBar("증상노트", null)
            if (demo > 0) DemoBanner()
            Panel {
                Title(if (running) "● 기록 중 (직전 1분 보관)" else "기록 꺼짐")
                if (running) { Small("소리 크기"); Bar(level / 32767.0 * 4, C.Green, Modifier.fillMaxWidth()) }
                Body("차에서 이상한 소리가 나면, 그 소리와 그 순간의 차량 상태를 함께 남깁니다.", C.Sub)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!running) BigButton("기록 시작", Modifier.weight(1f), enabled = canStart) { msg = startRecording() }
                    else BigButton("기록 끝내기", Modifier.weight(1f), primary = false) { RecordingService.stop(this@MainActivity) }
                    BigButton("주행 중 기록 화면", Modifier.weight(1f), primary = running) { openDrivingScreen() }
                }
                if (!canStart && !running) Body("기어가 P일 때만 기록을 시작할 수 있어요", C.Yellow)
                (msg ?: err)?.let { Body(it, C.Red) }
                if (auto.contains(ai.symptomnote.core.RecordingPolicy.MISSED_CAPTURE)) Body("자동 포착: $auto", C.Yellow) else Small("자동 포착: $auto")
            }
            LivePanel(live)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("기록 목록", Modifier.weight(1f), primary = false) { go(Screen.Records) }
                BigButton("증상·리포트", Modifier.weight(1f), primary = false) { go(Screen.Symptoms) }
            }
            BigButton("설정", Modifier.fillMaxWidth(), primary = false) { go(Screen.Settings) }
        }
    }

    @Composable
    private fun LivePanel(l: Live) = Panel {
        Title("지금 차량 상태")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Body("기어 ${if (l.gear == 0) "확인 중" else Conditions.gearName(l.gear)}")
                Body("속도 ${"%.0f".format(l.speedKmh)} km/h")
                Body("바깥 온도 ${l.outsideTempC?.let { "%.0f°C".format(it) } ?: "-"}")
                Body("주행거리 ${l.odometerKm?.let { "%.1f km".format(it) } ?: "-"}")
            }
            Column(Modifier.weight(1f)) {
                Body("브레이크 ${if (l.brake) "밟음" else "-"}")
                Body("핸들 ${"%.0f".format(l.steeringDeg)}°")
                Body("와이퍼 ${if (l.wiperOn) "작동" else "꺼짐"}")
                Body("방향지시등 ${if (l.turnOn) "켬" else "꺼짐"}")
            }
        }
    }

    companion object {
        const val PERM_SPEED = "android.car.permission.CAR_SPEED"
    }
}
