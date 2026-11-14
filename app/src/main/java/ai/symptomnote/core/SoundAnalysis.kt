package ai.symptomnote.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** 외부 라이브러리 없는 radix-2 반복형 FFT(제자리 계산). n 은 2의 거듭제곱. verify-app 5-4 와 같은 방식. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "n=$n 은 2의 거듭제곱이어야 함" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** 수리 전 대표 녹음 대비 수리 후 녹음의 변화. "판정"이 아니라 참고 지표다. */
data class BandChange(val band: Int, val beforeDb: Double, val afterDb: Double) {
    val deltaDb: Double get() = afterDb - beforeDb
    /** 특징 대역이 수리 전과 비슷하면 재발 기록으로 분류한다. */
    val recurrence: Boolean get() = deltaDb > SoundAnalysis.RECUR_DB
}

/**
 * 대역별 에너지(verify-app 5-4 실측으로 이론값과 일치 확인).
 * - Hann 창, 50% 겹침 프레임의 평균 파워(Welch). 프레임 길이는 약 85ms 에 가까운 2의 거듭제곱.
 * - 한쪽 파워 스펙트럼 합이 평균제곱(풀스케일=1)과 같도록 정규화 → 샘플레이트·길이가 달라도 같은 단위로 비교 가능.
 */
object SoundAnalysis {
    val BANDS = listOf(0.0 to 1000.0, 1000.0 to 2000.0, 2000.0 to 4000.0, 4000.0 to 8000.0, 8000.0 to 16000.0)
    val BAND_NAMES = listOf("0~1k", "1~2k", "2~4k", "4~8k", "8~16k")

    /** 수리 후 특징 대역 변화가 이 값(dB)보다 크면(덜 줄었으면) 재발 기록. 설계값, 실측으로 조정 필요. */
    const val RECUR_DB = -6.0
    private const val FLOOR = 1e-12

    /** 대역별 평균제곱 에너지. Nyquist 를 넘는 대역은 NaN. */
    fun bands(samples: ShortArray, sampleRate: Int): DoubleArray {
        val x = DoubleArray(samples.size) { samples[it] / 32768.0 }
        var n = 256
        while (n * 2 <= sampleRate * 0.085) n *= 2
        n *= 2
        if (x.size < n) { n = 256; while (n * 2 <= x.size) n *= 2 }
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
        val wss = win.sumOf { it * it }
        val power = DoubleArray(n / 2 + 1)
        var frames = 0
        var start = 0
        val re = DoubleArray(n); val im = DoubleArray(n)
        while (start + n <= x.size) {
            for (i in 0 until n) { re[i] = x[start + i] * win[i]; im[i] = 0.0 }
            Fft.transform(re, im)
            for (k in 0..n / 2) power[k] += re[k] * re[k] + im[k] * im[k]
            frames++
            start += n / 2
        }
        if (frames > 0) for (k in power.indices) {
            val one = if (k == 0 || k == n / 2) 1.0 else 2.0
            power[k] = power[k] / frames * one / (n * wss)
        }
        val binHz = sampleRate.toDouble() / n
        val nyq = sampleRate / 2.0
        return DoubleArray(BANDS.size) { b ->
            val (lo, hi) = BANDS[b]
            if (lo >= nyq) Double.NaN
            else {
                var e = 0.0
                for (k in power.indices) { val f = k * binHz; if (f >= lo && f < hi) e += power[k] }
                e
            }
        }
    }

    fun db(e: Double) = 10 * log10(maxOf(e, FLOOR))

    /** 가장 에너지가 큰 대역(수리 전 특징 대역). */
    fun strongest(bands: DoubleArray): Int = bands.indices.filter { !bands[it].isNaN() }.maxByOrNull { bands[it] } ?: 0

    /** 수리 전(before)에서 가장 강했던 대역이 수리 후(after)에서 몇 dB 바뀌었는지. */
    fun compare(before: DoubleArray, after: DoubleArray): BandChange {
        val b = strongest(before)
        val a = after[b].let { if (it.isNaN()) 0.0 else it }
        return BandChange(b, db(before[b]), db(a))
    }
}
