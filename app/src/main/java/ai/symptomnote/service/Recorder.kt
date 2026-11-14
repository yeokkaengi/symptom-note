package ai.symptomnote.service

import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.audio.TAG
import ai.symptomnote.core.AutoCaptureJudge
import ai.symptomnote.core.Conditions
import ai.symptomnote.core.Cond
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.Recording
import ai.symptomnote.data.Source
import ai.symptomnote.data.Symptom
import ai.symptomnote.vehicle.Live
import ai.symptomnote.vehicle.VehicleSignals
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 링 버퍼 저장 + 조건 스냅샷 + DB 기록. 작업 스레드에서 호출. */
object RecordSaver {
    data class Result(val ok: Boolean, val message: String, val recording: Recording? = null)

    fun recDir(c: Context) = File(c.filesDir, "rec")

    suspend fun save(c: Context, source: String, autoFor: Long? = null): Result {
        val snap = VehicleSignals.snapshot()
        val now = System.currentTimeMillis()
        val f = File(recDir(c), "${source.lowercase()}_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))}.wav")
        val s = RingRecorder.save(f)
        if (!s.ok) return Result(false, s.message)
        val r = Recording(t = now, file = f.absolutePath, source = source, snap = snap, sampleRate = s.sampleRate, peak = s.peak,
            symptomId = autoFor, autoForSymptomId = autoFor)
        val id = AppDb.get(c).dao().insertRecording(r)
        Log.i(TAG, "기록 저장 source=$source autoFor=$autoFor ${s.message} snap=$snap")
        return Result(true, s.message, r.copy(id = id))
    }
}

/**
 * 공통 조건이 만들어진 증상마다 자동 포착(verify 5-13, 5-20).
 * VehicleSignals 상태가 바뀔 때마다 "그 순간" 조건을 규칙과 맞춰 보고, 시작 순간 + 3초에 조용히 저장한다.
 * 저장된 기록은 그 증상에 바로 묶이고 목록에 "자동"으로 표시된다. 효과음·TTS 없음.
 */
object AutoCaptureEngine {
    private class Watch(val symptom: Symptom, val rule: Set<Cond>, val judge: AutoCaptureJudge = AutoCaptureJudge())

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watches: Map<Long, Watch> = emptyMap()
    private lateinit var app: Context
    private val _last = MutableStateFlow("자동 포착 기록 없음")
    val last: StateFlow<String> = _last.asStateFlow()

    fun start(c: Context) {
        app = c.applicationContext
        io.launch {
            AppDb.get(app).dao().autoSymptoms().collect { list ->
                main.post {
                    // 규칙이 같으면 판단기(쿨다운 상태)를 그대로 둔다.
                    watches = list.mapNotNull { s ->
                        val rule = Conditions.decode(s.rule)
                        if (rule.isEmpty()) null
                        else s.id to (watches[s.id]?.takeIf { it.rule == rule } ?: Watch(s, rule))
                    }.toMap()
                }
            }
        }
        VehicleSignals.onChange = ::onLive
    }

    private fun onLive(l: Live) {
        if (watches.isEmpty()) return
        val keys = Conditions.keys(ai.symptomnote.core.Snapshot.of(l.toSample(0, 0)))
        val now = SystemClock.elapsedRealtime()
        for (w in watches.values) {
            val at = w.judge.onSample(now, Conditions.matches(w.rule, keys)) ?: continue
            Log.i(TAG, "자동 포착 조건 시작: ${w.symptom.name} → ${at - now}ms 뒤 저장")
            main.postDelayed({
                io.launch {
                    val r = RecordSaver.save(app, Source.AUTO, w.symptom.id)
                    main.post { w.judge.onSaved(r.ok) }
                    _last.value = "${SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())} ${w.symptom.name} · ${if (r.ok) "저장" else "저장 안 함(${r.message})"}"
                }
            }, at - now)
        }
    }
}

/**
 * 마이크 포그라운드 서비스(verify 5-17). 화면이 보일 때 홈 화면 "기록 시작"으로만 시작한다
 * (화면 없이 시작한 마이크 FGS 는 Android 14 에서 막힘: verify 5-8, 5-7 재시험).
 * 다른 앱이 화면을 가려도 녹음이 계속된다. 알림: "증상노트 기록 중".
 */
class RecordingService : Service() {
    companion object {
        private const val CH = "recording"
        private const val ID = 1
        const val ACTION_STOP = "ai.symptomnote.STOP"
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        fun start(c: Context) { _error.value = null; c.startForegroundService(Intent(c, RecordingService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, RecordingService::class.java).setAction(ACTION_STOP)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RingRecorder.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "기록 상태", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("증상노트 기록 중")
            .setContentText("직전 10초만 기기 안에 보관합니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground 실패: $e")
            _error.value = "녹음을 시작하지 못했어요: ${e.javaClass.simpleName}"
            stopSelf(); return START_NOT_STICKY
        }
        if (!RingRecorder.start()) {
            _error.value = "마이크를 열지 못했어요(권한·다른 앱 사용 여부를 확인하세요)"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RingRecorder.stop()
        super.onDestroy()
    }
}
