package ai.symptomnote.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val TAG = "SymptomNote"

/** 16bit 모노 PCM WAV 읽기/쓰기. */
object WavIo {
    fun write(file: File, samples: ShortArray, count: Int, sampleRate: Int) {
        val dataBytes = count * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) body.putShort(samples[i])
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(header.array()); it.write(body.array()) }
    }

    class Pcm16(val sampleRate: Int, val samples: ShortArray)

    /** RIFF 청크를 순서대로 읽어 fmt/data 를 찾는다. 16bit PCM 만 지원(다채널이면 첫 채널). */
    fun read(file: File): Pcm16 {
        RandomAccessFile(file, "r").use { raf ->
            val all = ByteArray(raf.length().toInt()).also { raf.readFully(it) }
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            require(String(all, 0, 4) == "RIFF" && String(all, 8, 4) == "WAVE") { "RIFF/WAVE 헤더 아님" }
            var pos = 12
            var rate = 0; var ch = 1; var bits = 0; var fmt = 0
            while (pos + 8 <= all.size) {
                val id = String(all, pos, 4)
                val size = bb.getInt(pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    fmt = bb.getShort(body).toInt(); ch = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4); bits = bb.getShort(body + 14).toInt()
                } else if (id == "data") {
                    require(fmt == 1 && bits == 16) { "지원하지 않는 형식(format=$fmt, bits=$bits)" }
                    val frames = minOf(size, all.size - body) / (2 * ch)
                    return Pcm16(rate, ShortArray(frames) { bb.getShort(body + it * 2 * ch) })
                }
                pos = body + size + (size and 1)
            }
            throw IllegalArgumentException("data 청크 없음")
        }
    }

    fun peak(a: ShortArray, n: Int = a.size): Int {
        var p = 0
        for (i in 0 until n) { val v = abs(a[i].toInt()); if (v > p) p = v }
        return p
    }
}

/**
 * 직전 60초 링 버퍼 녹음 엔진(verify 5-3, 5-17). 48kHz 모노 16비트로 약 5.6MB.
 * - 수동 기록은 60초 전체를, 자동 포착은 마지막 10초만 저장한다.
 * - 48kHz 를 먼저 열고, 안 되면 44.1kHz(8~16kHz 대역 분석을 위해 16kHz 는 쓰지 않음).
 * - RecordingService(마이크 FGS) 안에서만 start 한다. 프로세스 전역 object 인 이유: 주행 화면·자동 포착이 같은 버퍼를 읽음.
 * - 원본 소리는 기기 안(filesDir/rec)에만 저장하고, 직전 60초만 메모리에 둔다.
 */
object RingRecorder {
    val RATES = listOf(48_000, 44_100)
    const val RING_SECONDS = 60
    const val CLIP_SECONDS = 10

    data class Saved(val ok: Boolean, val message: String, val sampleRate: Int = 0, val peak: Int = 0)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _level = MutableStateFlow(0)
    /** 최근 0.1초 peak(화면 표시용). */
    val level: StateFlow<Int> = _level.asStateFlow()

    @Volatile var rate = 0
        private set
    private val lock = Object()
    private var ring = ShortArray(0)
    private var ringWrite = 0
    private var ringFilled = 0
    @Volatile private var thread: Thread? = null

    val isRunning get() = thread != null

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        for (r in RATES) {
            try {
                val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
                if (rec.state == AudioRecord.STATE_INITIALIZED) { rate = r; return rec }
                rec.release()
                Log.w(TAG, "AudioRecord ${r}Hz 초기화 실패")
            } catch (e: Throwable) {
                Log.w(TAG, "AudioRecord ${r}Hz 예외: $e")
            }
        }
        return null
    }

    /** 엔진 켜기. 이미 켜져 있으면 true. 마이크를 열지 못하면 false. */
    fun start(): Boolean {
        if (thread != null) return true
        val rec = open() ?: return false
        synchronized(lock) { ring = ShortArray(rate * RING_SECONDS); ringWrite = 0; ringFilled = 0 }
        try {
            rec.startRecording()
        } catch (e: Throwable) {
            Log.w(TAG, "startRecording 실패: $e"); rec.release(); return false
        }
        val t = Thread({ loop(rec) }, "ring-recorder")
        thread = t
        _running.value = true
        t.start()
        Log.i(TAG, "링 버퍼 켜짐 ${rate}Hz")
        return true
    }

    fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun loop(rec: AudioRecord) {
        val chunk = ShortArray(rate / 10)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) { Log.w(TAG, "read 오류 $n"); Thread.sleep(100); continue }
                synchronized(lock) {
                    for (i in 0 until n) { ring[ringWrite] = chunk[i]; ringWrite = (ringWrite + 1) % ring.size }
                    ringFilled = minOf(ring.size, ringFilled + n)
                }
                _level.value = WavIo.peak(chunk, n)
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.w(TAG, "링 버퍼 오류: $e")
        } finally {
            runCatching { rec.stop() }
            rec.release()
            thread = null
            _running.value = false
            _level.value = 0
            Log.i(TAG, "링 버퍼 꺼짐")
        }
    }

    /** 링 버퍼 직전 최대 [lastSec]초를 시간순으로 복사. 엔진이 꺼져 있거나 비어 있으면 null. */
    fun grab(lastSec: Int): WavIo.Pcm16? {
        if (thread == null) return null
        val r = rate
        val s = synchronized(lock) {
            val n = minOf(ringFilled, r * lastSec)
            val start = (ringWrite - n + ring.size) % ring.size
            ShortArray(n) { ring[(start + it) % ring.size] }
        }
        return if (s.isEmpty()) null else WavIo.Pcm16(r, s)
    }

    /** 링 버퍼 직전 최대 [lastSec]초를 WAV 저장(자동 포착은 10초). 엔진이 꺼져 있으면 저장하지 않는다. */
    fun save(file: File, lastSec: Int = CLIP_SECONDS): Saved {
        if (thread == null) return Saved(false, "기록이 꺼져 있어요")
        val pcm = grab(lastSec) ?: return Saved(false, "아직 녹음된 소리가 없어요")
        return write(file, pcm.samples, pcm.sampleRate)
    }

    fun write(file: File, s: ShortArray, r: Int): Saved = try {
        WavIo.write(file, s, s.size, r)
        Saved(true, "${file.name} · ${"%.1f".format(s.size.toDouble() / r)}초", r, WavIo.peak(s))
    } catch (e: Throwable) {
        Saved(false, "저장 실패: ${e.message}")
    }
}

/** 녹음 재생(한 번에 하나). */
object Player {
    private var mp: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    fun toggle(path: String?) {
        if (path == null) return
        if (_playing.value == path) { stop(); return }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare(); start()
            }
            _playing.value = path
        } catch (e: Throwable) {
            Log.w(TAG, "재생 실패: $e"); stop()
        }
    }

    fun stop() {
        runCatching { mp?.release() }
        mp = null
        _playing.value = null
    }
}
