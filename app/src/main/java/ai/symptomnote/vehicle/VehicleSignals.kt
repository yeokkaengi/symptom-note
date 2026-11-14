package ai.symptomnote.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.symptomnote.audio.TAG
import ai.symptomnote.core.DriveSample
import ai.symptomnote.core.Snapshot
import ai.symptomnote.data.AppDb
import ai.symptomnote.data.DriveSecond
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 지금 차량 상태. 속도 km/h, 핸들 도, 기어는 VehicleGear 값(0=아직 모름). */
data class Live(
    val speedKmh: Float = 0f,
    val gear: Int = 0,
    val brake: Boolean = false,
    val steeringDeg: Float = 0f,
    val outsideTempC: Float? = null,
    val wiperOn: Boolean = false,
    val turnOn: Boolean = false,
    val odometerKm: Float? = null
) {
    val parked get() = gear == GEAR_P
    fun toSample(tripId: Long, t: Long) = DriveSample(tripId, t, speedKmh, brake, steeringDeg, gear, outsideTempC, wiperOn, turnOn)

    companion object { const val GEAR_P = 4 }
}

/**
 * 주행 신호 구독(verify 5-5 실측 경로 그대로).
 * - 표준 CarPropertyManager: 속도 PERF_VEHICLE_SPEED(m/s → km/h, 10Hz), 기어 GEAR_SELECTION, 바깥 온도 ENV_OUTSIDE_TEMPERATURE
 * - Pleos Vehicle SDK: 브레이크, 핸들 각도, 앞유리 와이퍼, 방향지시등, 누적 주행거리
 *   (표준 속성으로는 권한이 없어 못 읽는 항목들. verify 탭 2·3 비교)
 * - 1초마다 요약을 만들어 기어가 P 가 아닐 때 트립 단위로 저장(평소 주행 기준률 계산용).
 * - 앱 프로세스가 살아 있는 동안 동작(App.onCreate 에서 시작).
 */
object VehicleSignals {
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 신호별 구독 상태(설정 화면 진단용). */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    /** 상태가 바뀔 때마다 메인 스레드에서 호출(자동 포착 판단). */
    @Volatile var onChange: ((Live) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val carCallbacks = mutableListOf<CarPropertyManager.CarPropertyEventCallback>()

    private class Ev(val t: Long, val live: Live)
    private val history = ArrayDeque<Ev>()
    private var tripId = 0L
    private var started = false

    private fun setStatus(k: String, v: String) {
        synchronized(_status) { _status.value = _status.value + (k to v) }
    }

    private fun update(f: (Live) -> Live) {
        val now = SystemClock.elapsedRealtime()
        val l = synchronized(history) {
            val n = f(_live.value)
            _live.value = n
            history.addLast(Ev(now, n))
            while (history.isNotEmpty() && now - history.first().t > 12_000) history.removeFirst()
            n
        }
        main.post { onChange?.let { runCatching { it(l) } } }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        startCar()
        startPleos()
        main.postDelayed(::tick, 1000)
    }

    /** CAR_SPEED 권한을 나중에 받았을 때 표준 속성 구독을 다시 한다. */
    fun restartCar() {
        val m = props
        synchronized(carCallbacks) { carCallbacks.forEach { cb -> runCatching { m?.unregisterCallback(cb) } }; carCallbacks.clear() }
        runCatching { car?.disconnect() }
        car = null; props = null
        startCar()
    }

    @Suppress("DEPRECATION")
    private fun startCar() {
        try {
            val c = Car.createCar(app) ?: throw IllegalStateException("Car.createCar() null")
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props = m
            fun sub(name: String, id: Int, onValue: (Any?) -> Unit) {
                try {
                    val cfg = m.getCarPropertyConfig(id)
                    val rate = cfg?.maxSampleRate?.takeIf { it > 0f } ?: CarPropertyManager.SENSOR_RATE_ONCHANGE
                    val cb = object : CarPropertyManager.CarPropertyEventCallback {
                        override fun onChangeEvent(value: CarPropertyValue<*>) = onValue(value.value)
                        override fun onErrorEvent(propertyId: Int, areaId: Int) = setStatus(name, "오류 이벤트")
                    }
                    val ok = m.registerCallback(cb, id, rate)
                    synchronized(carCallbacks) { carCallbacks += cb }
                    setStatus(name, if (ok) "구독 중(표준)" else "구독 실패(registerCallback=false)")
                } catch (e: Throwable) {
                    setStatus(name, "사용 불가: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
                }
            }
            sub("속도", VehiclePropertyIds.PERF_VEHICLE_SPEED) { v -> (v as? Number)?.let { n -> update { it.copy(speedKmh = n.toFloat() * 3.6f) } } }
            sub("기어", VehiclePropertyIds.GEAR_SELECTION) { v -> (v as? Number)?.let { n -> update { it.copy(gear = n.toInt()) } } }
            sub("바깥 온도", VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE) { v -> (v as? Number)?.let { n -> update { it.copy(outsideTempC = n.toFloat()) } } }
        } catch (e: Throwable) {
            setStatus("표준 차량 속성", "연결 실패: ${e.message}")
        }
    }

    private fun startPleos() {
        try {
            val v = Vehicle(app).also { it.initialize(); vehicle = it }
            fun guard(name: String, block: () -> Unit) {
                try { block(); setStatus(name, "구독 중(Pleos)") } catch (e: Throwable) { setStatus(name, "사용 불가: ${e.message}") }
            }
            fun failed(name: String) = { e: Exception -> setStatus(name, "onFailed: ${e.message}") }
            guard("브레이크") {
                v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                    override fun onBrakePedalStatusUpdated(status: Boolean?) { if (status != null) update { it.copy(brake = status) } }
                    override fun onFailed(e: Exception) = failed("브레이크")(e)
                })
            }
            guard("핸들 각도") {
                v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                    override fun onSteeringWheelAngleUpdated(angle: Float?) { if (angle != null) update { it.copy(steeringDeg = angle) } }
                    override fun onFailed(e: Exception) = failed("핸들 각도")(e)
                })
            }
            guard("와이퍼") {
                v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
                    override fun onWiperStateUpdated(state: WiperState?) { if (state != null) update { it.copy(wiperOn = state == WiperState.ON) } }
                    override fun onFailed(e: Exception) = failed("와이퍼")(e)
                })
            }
            guard("방향지시등") {
                v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                    override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { if (state != null) update { it.copy(turnOn = state.name != "OFF") } }
                    override fun onFailed(e: Exception) = failed("방향지시등")(e)
                })
            }
            guard("주행거리") {
                v.getOdometer().registerDistanceDriven(object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(value: Float?) { if (value != null) update { it.copy(odometerKm = value) } }
                    override fun onFailed(e: Exception) = failed("주행거리")(e)
                })
            }
        } catch (e: Throwable) {
            setStatus("Pleos Vehicle", "초기화 실패: ${e.message}")
        }
    }

    /** 직전 [sec]초 기록으로 조건 스냅샷을 만든다(수동·자동 기록 저장 순간). */
    fun snapshot(sec: Int = 10): Snapshot {
        val now = SystemClock.elapsedRealtime()
        val cur = _live.value
        val w = synchronized(history) { history.filter { now - it.t <= sec * 1000L }.map { it.live } } + cur
        return Snapshot(
            w.minOf { it.speedKmh }, w.maxOf { it.speedKmh }, w.map { it.speedKmh }.average().toFloat(),
            w.any { it.brake }, w.minOf { it.steeringDeg }, w.maxOf { it.steeringDeg },
            cur.gear, cur.outsideTempC, w.any { it.wiperOn }, w.any { it.turnOn }, cur.odometerKm
        )
    }

    /** 1초 요약: 평균 속도, 그 1초 안 브레이크 여부, 절대값이 가장 큰 핸들 각도. */
    private fun tick() {
        main.postDelayed(::tick, 1000)
        val now = SystemClock.elapsedRealtime()
        val cur = _live.value
        if (cur.gear == 0 || cur.parked) { tripId = 0; return }
        if (tripId == 0L) tripId = System.currentTimeMillis()
        val w = synchronized(history) { history.filter { now - it.t <= 1000 }.map { it.live } } + cur
        val l = cur.copy(
            speedKmh = w.map { it.speedKmh }.average().toFloat(),
            brake = w.any { it.brake },
            steeringDeg = w.maxBy { abs(it.steeringDeg) }.steeringDeg
        )
        val row = DriveSecond(s = l.toSample(tripId, System.currentTimeMillis()))
        io.launch { runCatching { AppDb.get(app).dao().insertSecond(row) }.onFailure { Log.w(TAG, "1초 요약 저장 실패: $it") } }
    }
}
