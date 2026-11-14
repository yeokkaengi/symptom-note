package ai.symptomnote.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.symptomnote.audio.RingRecorder
import ai.symptomnote.core.BandChange
import ai.symptomnote.core.SoundAnalysis
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.Recording
import ai.symptomnote.data.Source
import ai.symptomnote.service.RecordSaver
import ai.symptomnote.service.SymptomAnalyzer
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * 동승석 착석 값 해석 상수(verify 5-2 실측).
 * 이 에뮬레이터의 Pleos getCurrentSeatOccupancy 는 좌석이 비어 있을 때 true 를 돌려준다(반전).
 * 그래서 "SDK 값이 false 일 때 사람이 앉아 있음"으로 해석한다. 해석은 이 상수 한 곳에서만 한다.
 */
const val SEAT_OCCUPIED_WHEN_SDK_VALUE = false

/**
 * 정차 자가 점검 · 창문(verify 5-1, 5-2, 5-3). 실차 전제 기능.
 * 확인 창 → 동승석 착석 확인(사람 있으면 중단) → 창문 제어 요청 → 4초 녹음(링 버퍼) → 원래 위치로 되돌림 → 첫 점검(기준)과 비교.
 * verify 5-1: setWindowPosition 에 onComplete 가 와도 값이 바뀌지 않은 적이 있다(11-05). 11-13 시험에서는 목표 값에 도달했다.
 * 그래서 요청 결과가 아니라 다시 읽은 값으로 실제 변화 여부를 표시한다.
 */
class WindowChecker(private val context: Context) {
    private val _steps = MutableStateFlow<List<String>>(emptyList())
    val steps: StateFlow<List<String>> = _steps.asStateFlow()
    @Volatile var running = false
        private set

    private val window = WindowArea(WindowValue.ROW_1_LEFT)
    private val passenger = SeatArea(SeatValue.ROW_1_RIGHT)

    private fun step(s: String) { _steps.value = _steps.value + s }

    /** 콜백 1번을 작업 스레드에서 기다린다. 결과: 값 / 실패 메시지(문자열) / 시간 초과(null). */
    private fun <T> await(call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): Result<T>? {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        try {
            call({ v -> if (latch.count > 0) { out = Result.success(v); latch.countDown() } },
                { e -> if (latch.count > 0) { out = Result.failure(e); latch.countDown() } })
        } catch (e: Exception) { return Result.failure(e) }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    fun run() {
        if (running) return
        running = true
        _steps.value = emptyList()
        Thread({
            var v: Vehicle? = null
            try {
                if (!VehicleSignals.live.value.parked) { step("중단: 기어가 P일 때만 점검해요"); return@Thread }
                if (!RingRecorder.isRunning) { step("중단: 먼저 홈에서 '기록 시작'을 눌러 녹음을 켜 주세요"); return@Thread }
                v = Vehicle(context.applicationContext).also { it.initialize() }
                val seat = v.getSeat()
                val occ = await<Boolean?> { ok, fail -> seat.getCurrentSeatOccupancy(passenger, ok, fail) }
                val raw = occ?.getOrNull()
                step("동승석 착석 값(SDK)=${raw ?: "알 수 없음"} → ${when (raw) { null -> "확인 못 함"; SEAT_OCCUPIED_WHEN_SDK_VALUE -> "사람 있음"; else -> "비어 있음" }}")
                if (raw == null) { step("중단: 착석을 확인하지 못해 안전을 위해 실행하지 않아요"); return@Thread }
                if (raw == SEAT_OCCUPIED_WHEN_SDK_VALUE) { step("중단: 동승석에 사람이 있어 실행하지 않아요"); return@Thread }

                val w = v.getWindow()
                var target = 5
                await<Unit> { ok, fail ->
                    w.checkWindowPositionCapabilities(onComplete = { _, bounds ->
                        bounds[window]?.let { b -> target = (b.minValue + b.maxValue) / 2 }; ok(Unit)
                    }, onFailed = fail)
                }
                val orig = await<Int?> { ok, fail -> w.getCurrentWindowPosition(window, ok, fail) }?.getOrNull()
                step("운전석 창문 현재 값=${orig ?: "알 수 없음"} → 목표 $target 로 제어 요청")
                val req = await<Int?> { ok, fail -> w.setWindowPosition(window, target, onComplete = { _, value -> ok(value) }, onFailed = fail) }
                step("제어 요청 결과: ${req?.fold({ "완료 응답" }, { "실패 ${it.message}" }) ?: "응답 없음"}")
                Thread.sleep(4000)
                val after = await<Int?> { ok, fail -> w.getCurrentWindowPosition(window, ok, fail) }?.getOrNull()
                step(if (after == target) "4초 뒤 다시 읽은 값=$after (목표 도달)"
                     else "4초 뒤 다시 읽은 값=$after (목표 $target 와 다름 → 실제로 움직이지 않음. verify 5-1 과 같은 결과)")
                val saved = runBlocking { RecordSaver.save(context, Source.WINDOW) }
                step(if (saved.ok) "녹음 저장: ${saved.message}" else "녹음 저장 안 됨: ${saved.message}")
                if (orig != null) {
                    val back = await<Int?> { ok, fail -> w.setWindowPosition(window, orig, onComplete = { _, value -> ok(value) }, onFailed = fail) }
                    step("원래 값 $orig 로 되돌림 요청: ${back?.fold({ "완료 응답" }, { "실패 ${it.message}" }) ?: "응답 없음"}")
                }
                saved.recording?.let { compare(it) }
            } catch (e: Throwable) {
                step("오류: ${e.javaClass.simpleName} ${e.message ?: ""}")
            } finally {
                runCatching { v?.release() }
                running = false
            }
        }, "window-check").start()
    }

    /** 첫 창문 점검 녹음을 "수리 전 기준"으로 삼아 비교한다. */
    private fun compare(now: Recording) {
        val first = runBlocking { AppDb.get(context).dao().windowChecksNow() }.firstOrNull { it.file != null && it.id != now.id }
        if (first == null) { step("첫 점검이라 이 녹음을 비교 기준으로 저장했어요"); return }
        val a = first.file?.let { SymptomAnalyzer.bands(it) }
        val b = now.file?.let { SymptomAnalyzer.bands(it) }
        if (a == null || b == null) { step("비교 못 함: 녹음 파일을 읽을 수 없음"); return }
        val c: BandChange = SoundAnalysis.compare(a, b)
        step("기준 대비 특징 대역(${SoundAnalysis.BAND_NAMES[c.band]}Hz) ${"%+.1f".format(c.deltaDb)}dB (참고 지표)")
    }
}
