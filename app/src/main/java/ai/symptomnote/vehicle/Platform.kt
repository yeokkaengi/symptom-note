package ai.symptomnote.vehicle

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.symptomnote.audio.TAG
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 주행 제한 상태 구독(verify 5-9: 기어 D 에서 isRequiresDistractionOptimization=true, P 에서 false).
 * 화면마다 하나 만들고 release 한다.
 */
class UxWatcher(context: Context) {
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null

    init {
        try {
            val c = Car.createCar(context) ?: throw IllegalStateException("Car.createCar() null")
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = m
            _restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> _restricted.value = r.isRequiresDistractionOptimization }
        } catch (e: Throwable) {
            Log.w(TAG, "CarUxRestrictionsManager 사용 불가: $e")
        }
    }

    fun release() {
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
    }
}

/**
 * Pleos TTS(ON_DEVICE, ko-KR) + 앱이 직접 잡는 오디오 포커스(verify 5-6, 5-18, 5-21b).
 * - Pleos TTS 는 포커스를 요청하지 않아 내비 안내와 겹침(5-11, 5-21b) → speak 앞에서
 *   GAIN_TRANSIENT_MAY_DUCK + USAGE_ASSISTANCE_NAVIGATION_GUIDANCE 로 요청, onDone/onError/onStop 뒤 반납.
 * - addEventListener 는 initialize 성공 뒤에 불러야 한다(5-6: 먼저 부르면 UninitializedPropertyAccessException).
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var focus: AudioFocusRequest? = null
    @Volatile var onEnd: (() -> Unit)? = null

    private val events = object : EventListener {
        override fun onStart() {}
        override fun onReady() {}
        override fun onDone() { main.post { finish("onDone") } }
        override fun onError(error: ErrorType) { main.post { finish("onError $error") } }
        override fun onStop() { main.post { finish("onStop") } }
        override fun onUpdatedRms(rms: Double) {}
    }

    init { init() }

    private fun init() {
        try {
            val e = TextToSpeech(app, Mode.ON_DEVICE)
            engine = e
            e.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        try {
                            e.addEventListener(events)
                            e.setLocale(Locale.forLanguageTag("ko-KR"))
                            ready = true
                            pending?.let { pending = null; speakNow(it) }
                        } catch (x: Throwable) { Log.w(TAG, "TTS 준비 실패: $x") }
                    }
                }
                override fun onFailure(error: ErrorType) { Log.w(TAG, "TTS initialize 실패: $error") }
            })
        } catch (x: Throwable) {
            Log.w(TAG, "TTS 생성 실패: $x")
        }
    }

    fun say(text: String) {
        if (ready) speakNow(text) else pending = text
    }

    private fun speakNow(text: String) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        val r = audio.requestAudioFocus(req)
        if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
        Log.i(TAG, "TTS 앞 오디오 포커스 요청 → $r")
        try { engine?.speak(text) } catch (x: Throwable) { Log.w(TAG, "speak 실패: $x"); finish("speak 예외") }
    }

    private fun finish(why: String) {
        focus?.let { Log.i(TAG, "오디오 포커스 반납($why) → ${audio.abandonAudioFocusRequest(it)}") }
        focus = null
        onEnd?.invoke()
    }

    fun release() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        runCatching { engine?.release() }
        engine = null
    }
}
