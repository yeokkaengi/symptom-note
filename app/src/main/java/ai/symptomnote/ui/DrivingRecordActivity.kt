package ai.symptomnote.ui

import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.audio.TAG
import ai.symptomnote.service.RecordSaver
import ai.symptomnote.vehicle.Speaker
import ai.symptomnote.vehicle.UxWatcher
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 주행 중 수동 기록 화면(verify 5-9, 5-10, 5-21, 5-21b).
 * - 매니페스트: distractionOptimized + 별도 taskAffinity, 열 때 FLAG_ACTIVITY_NEW_TASK.
 * - 화면 대부분을 차지하는 큰 버튼 하나 + 한 줄 상태. 애니메이션·리플 없음.
 * - 누르면 즉시 "기록 중" → 직전 60초 소리 + 1초 조건 기록 저장(추정 지점 앞뒤 5초가 대표 구간) → 저장 성공 뒤에만 TTS "기록했어요"(오디오 포커스 포함).
 * - 주행 중(CarUxRestrictions)에는 다른 화면으로 가는 버튼을 숨긴다.
 */
class DrivingRecordActivity : ComponentActivity() {
    private lateinit var ux: UxWatcher
    private lateinit var speaker: Speaker
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("소리가 난 지점은 앱이 찾아요")
    private var pressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ux = UxWatcher(this)
        speaker = Speaker(this).also { it.onEnd = { busy = false } }
        setContent { NoteTheme { Screen() } }
    }

    override fun onDestroy() {
        speaker.release()
        ux.release()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val running by RingRecorder.running.collectAsStateWithLifecycle()
        val restricted by ux.restricted.collectAsStateWithLifecycle()
        val src = remember { MutableInteractionSource() }
        // "기록 중" 이 실제로 그려진 시각을 남긴다(목표 0.25초 이내).
        SideEffect {
            val p = pressedAt
            if (busy && p != 0L) Choreographer.getInstance().postFrameCallback {
                if (pressedAt == p) { Log.i(TAG, "\"기록 중\" 표시 +${SystemClock.uptimeMillis() - p}ms"); pressedAt = 0L }
            }
        }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .background(if (busy) Color(0xFF424242) else C.Yellow)
                    .clickable(interactionSource = src, indication = null, role = Role.Button, enabled = !busy) { onPress() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (busy) "기록 중" else "방금 소리 났어요", color = if (busy) Color.White else Color.Black,
                    fontSize = 64.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Text("안전할 때 누르세요 · 최근 1분이 저장돼요", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${if (running) "● 녹음 켜짐" else "녹음 꺼짐"} · $status", color = Color.White, fontSize = 26.sp,
                    modifier = Modifier.weight(1f), maxLines = 1)
                if (!restricted) BigButton("주차 화면", primary = false) {
                    startActivity(Intent(this@DrivingRecordActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private fun onPress() {
        val t0 = SystemClock.uptimeMillis()
        if (!RingRecorder.isRunning) {
            status = "녹음이 꺼져 있어요. 주차 후 홈에서 '기록 시작'"
            return
        }
        pressedAt = t0
        busy = true
        status = "기록 중"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { RecordSaver.saveManual(this@DrivingRecordActivity) }
            Log.i(TAG, "수동 기록 저장 +${SystemClock.uptimeMillis() - t0}ms ok=${r.ok} ${r.message}")
            if (r.ok) {
                status = "기록했어요"
                speaker.say("기록했어요")
                delay(4000) // TTS 콜백이 안 와도 버튼이 묶이지 않게
                busy = false
            } else {
                status = r.message
                busy = false
            }
        }
    }
}
