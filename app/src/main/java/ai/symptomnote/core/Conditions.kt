package ai.symptomnote.core

import kotlin.math.abs
import kotlin.math.floor

/** 조건 범주. 같은 범주 안에서는 OR, 범주끼리는 AND 로 맞춰 본다. */
enum class Cat { BRAKE, SPEED, STEER, TEMP, WIPER, TURN, GEAR }

/** 조건 하나. 예) Cond(SPEED, "20") = 시속 20~30km 구간, Cond(TEMP, "COLD") = 바깥 10도 이하. */
data class Cond(val cat: Cat, val v: String)

/** 1초 요약 한 줄(평소 주행 기록). 속도는 km/h, 핸들은 도, 기어는 VehicleGear 값(P=4, D=8, R=2, N=1). */
data class DriveSample(
    val tripId: Long,
    val t: Long,
    val speedKmh: Float,
    val brake: Boolean,
    val steeringDeg: Float,
    val gear: Int,
    val outsideTempC: Float?,
    val wiperOn: Boolean,
    val turnOn: Boolean
)

/** 기록 순간(직전 10초)의 조건 스냅샷. Room 에는 Recording 에 @Embedded 로 들어간다. */
data class Snapshot(
    val speedMinKmh: Float,
    val speedMaxKmh: Float,
    val speedAvgKmh: Float,
    val brake: Boolean,
    val steerMinDeg: Float,
    val steerMaxDeg: Float,
    val gear: Int,
    val outsideTempC: Float?,
    val wiperOn: Boolean,
    val turnOn: Boolean,
    val odometerKm: Float?
) {
    companion object {
        /** 1초 요약 한 줄을 "그 순간" 스냅샷으로(자동 포착·수리 후 통과 횟수 계산용). */
        fun of(s: DriveSample) = Snapshot(s.speedKmh, s.speedKmh, s.speedKmh, s.brake, s.steeringDeg, s.steeringDeg,
            s.gear, s.outsideTempC, s.wiperOn, s.turnOn, null)

        /** 여러 줄(같은 트립, 시간순)을 하나의 창 스냅샷으로. 마지막 줄의 기어·온도·와이퍼·방향지시등을 쓴다. */
        fun ofWindow(w: List<DriveSample>): Snapshot {
            require(w.isNotEmpty())
            val last = w.last()
            return Snapshot(
                w.minOf { it.speedKmh }, w.maxOf { it.speedKmh }, w.map { it.speedKmh }.average().toFloat(),
                w.any { it.brake }, w.minOf { it.steeringDeg }, w.maxOf { it.steeringDeg },
                last.gear, last.outsideTempC, w.any { it.wiperOn }, w.any { it.turnOn }, null
            )
        }
    }
}

/** 조건 후보별 통계. inclusion = 기록 중 그 조건이 있었던 비율, base = 평소 주행 10초 창 중 그 조건이 있었던 비율. */
data class CondStat(
    val cond: Cond,
    val hits: Int,
    val total: Int,
    val base: Double?,
    val adopted: Boolean
) {
    val inclusion: Double get() = if (total == 0) 0.0 else hits.toDouble() / total
    val lift: Double? get() = base?.let { if (it <= 0.0) Double.POSITIVE_INFINITY else inclusion / it }
}

data class Analysis(
    val stats: List<CondStat>,
    /** 채택된 공통 조건(자동 포착 규칙의 후보). */
    val rule: Set<Cond>,
    /** 공통 조건을 모두 만족한 기록 수. */
    val ruleHits: Int,
    val total: Int,
    /** 평소 주행에서 공통 조건을 모두 만족한 10초 창의 비율. 평소 기록이 부족하면 null. */
    val ruleBase: Double?,
    val baselineWindows: Int
) {
    val baselineEnough get() = baselineWindows >= Conditions.MIN_BASELINE_WINDOWS
}

object Conditions {
    // ---- 아래 숫자는 모두 설계값입니다. 실측으로 조정 필요 ----
    /** 공통 조건 채택: 기록 중 포함률 최소값. 설계값, 실측으로 조정 필요. */
    const val MIN_INCLUSION = 0.75
    /** 공통 조건 채택: 포함률/기준률 최소값. 설계값, 실측으로 조정 필요. */
    const val MIN_LIFT = 2.0
    /** 공통 조건 계산에 필요한 최소 기록 수. */
    const val MIN_RECORDS = 2
    /** 기준률을 믿을 수 있는 최소 평소 주행 창 수(약 30초 주행). 설계값, 실측으로 조정 필요. */
    const val MIN_BASELINE_WINDOWS = 30
    /** 스냅샷 창 길이(초). 녹음 링 버퍼 길이와 같다. */
    const val WINDOW_SEC = 10
    const val SPEED_BAND_KMH = 10
    /** 핸들 각도 구간 경계(절대값, 도). 설계값, 실측으로 조정 필요. */
    const val STEER_SMALL_DEG = 15f
    const val STEER_BIG_DEG = 90f
    /** 바깥 온도 구간 경계(°C). 설계값, 실측으로 조정 필요. */
    const val TEMP_COLD_C = 10f
    const val TEMP_HOT_C = 25f

    fun gearName(g: Int) = when (g) { 4 -> "P"; 8 -> "D"; 2 -> "R"; 1 -> "N"; 0 -> "?"; else -> "기타" }

    private fun speedBand(kmh: Float) = (floor(kmh / SPEED_BAND_KMH).toInt() * SPEED_BAND_KMH).coerceAtLeast(0)

    /** 스냅샷이 가진 조건 전부. 속도는 최소~최대 사이에 걸친 10km/h 구간 모두. */
    fun keys(s: Snapshot): Set<Cond> = buildSet {
        if (s.brake) add(Cond(Cat.BRAKE, "ON"))
        var b = speedBand(s.speedMinKmh)
        val top = speedBand(s.speedMaxKmh)
        while (b <= top) { add(Cond(Cat.SPEED, b.toString())); b += SPEED_BAND_KMH }
        val steer = maxOf(abs(s.steerMinDeg), abs(s.steerMaxDeg))
        add(Cond(Cat.STEER, when { steer < STEER_SMALL_DEG -> "STRAIGHT"; steer < STEER_BIG_DEG -> "SMALL"; else -> "BIG" }))
        s.outsideTempC?.let { add(Cond(Cat.TEMP, when { it <= TEMP_COLD_C -> "COLD"; it <= TEMP_HOT_C -> "MILD"; else -> "HOT" })) }
        if (s.wiperOn) add(Cond(Cat.WIPER, "ON"))
        if (s.turnOn) add(Cond(Cat.TURN, "ON"))
        add(Cond(Cat.GEAR, gearName(s.gear)))
    }

    /** 규칙과 맞는지: 규칙에 있는 범주마다 하나 이상 겹쳐야 한다(범주 안 OR, 범주끼리 AND). 빈 규칙은 false. */
    fun matches(rule: Set<Cond>, keys: Set<Cond>): Boolean {
        if (rule.isEmpty()) return false
        return rule.groupBy { it.cat }.all { (_, conds) -> conds.any { it in keys } }
    }

    /** 평소 주행 1초 요약 → 트립별 10초 슬라이딩 창(1초 간격) 스냅샷. 입력은 트립·시간순이 아니어도 된다. */
    fun windows(samples: List<DriveSample>): List<Snapshot> {
        val out = ArrayList<Snapshot>(samples.size)
        samples.groupBy { it.tripId }.values.forEach { trip ->
            val s = trip.sortedBy { it.t }
            var from = 0
            for (i in s.indices) {
                while (s[i].t - s[from].t >= WINDOW_SEC * 1000L) from++
                out += Snapshot.ofWindow(s.subList(from, i + 1))
            }
        }
        return out
    }

    /** 공통 조건 계산. records = 증상에 묶인 기록 스냅샷, baseline = windows() 결과. */
    fun analyze(records: List<Snapshot>, baseline: List<Snapshot>): Analysis {
        val recKeys = records.map { keys(it) }
        val baseKeys = baseline.map { keys(it) }
        val enoughBase = baseline.size >= MIN_BASELINE_WINDOWS
        val candidates = recKeys.flatten().toSet()
        val stats = candidates.map { c ->
            val hits = recKeys.count { c in it }
            val base = if (enoughBase) baseKeys.count { c in it }.toDouble() / baseKeys.size else null
            val st = CondStat(c, hits, records.size, base, false)
            val adopted = records.size >= MIN_RECORDS && base != null &&
                st.inclusion >= MIN_INCLUSION && (st.lift ?: 0.0) >= MIN_LIFT
            st.copy(adopted = adopted)
        }.sortedWith(compareByDescending<CondStat> { it.adopted }.thenByDescending { it.inclusion }.thenBy { it.cond.cat })
        val rule = stats.filter { it.adopted }.map { it.cond }.toSet()
        val ruleHits = recKeys.count { matches(rule, it) }
        val ruleBase = if (enoughBase && rule.isNotEmpty()) baseKeys.count { matches(rule, it) }.toDouble() / baseKeys.size else null
        return Analysis(stats, rule, ruleHits, records.size, ruleBase, baseline.size)
    }

    /** 수리 후 "같은 조건 통과 횟수": 1초 요약에서 규칙이 거짓→참으로 바뀐 횟수(트립별). */
    fun countPasses(rule: Set<Cond>, samples: List<DriveSample>): Int {
        if (rule.isEmpty()) return 0
        var n = 0
        samples.groupBy { it.tripId }.values.forEach { trip ->
            var prev = false
            trip.sortedBy { it.t }.forEach { s ->
                val m = matches(rule, keys(Snapshot.of(s)))
                if (m && !prev) n++
                prev = m
            }
        }
        return n
    }

    // ---------------- 표시용 문장 ----------------

    fun label(c: Cond): String = when (c.cat) {
        Cat.BRAKE -> "브레이크 중"
        Cat.SPEED -> c.v.toInt().let { if (it == 0) "정차~시속 10km" else "시속 $it~${it + SPEED_BAND_KMH}km" }
        Cat.STEER -> when (c.v) { "STRAIGHT" -> "직진(핸들 15도 미만)"; "SMALL" -> "핸들 15~90도"; else -> "핸들 크게(90도 이상)" }
        Cat.TEMP -> when (c.v) { "COLD" -> "차가운 날(바깥 10도 이하)"; "MILD" -> "바깥 10~25도"; else -> "더운 날(바깥 25도 초과)" }
        Cat.WIPER -> "와이퍼 작동"
        Cat.TURN -> "방향지시등 켬"
        Cat.GEAR -> "기어 ${c.v}"
    }

    private val ORDER = listOf(Cat.TEMP, Cat.SPEED, Cat.BRAKE, Cat.STEER, Cat.WIPER, Cat.TURN, Cat.GEAR)

    /**
     * 지금 상태([keys])가 규칙의 어느 조건을 만족하지 않는지, 범주마다 한 문장(예: "바깥 온도가 10도 이하가 아님").
     * 모두 만족하면 빈 목록. 자동 포착이 왜 저장하지 않는지 시험·시연 중에 바로 보여 주기 위한 것.
     */
    fun unmet(rule: Set<Cond>, keys: Set<Cond>): List<String> = ORDER.mapNotNull { cat ->
        val cs = rule.filter { it.cat == cat }
        if (cs.isEmpty() || cs.any { it in keys }) return@mapNotNull null
        when (cat) {
            Cat.BRAKE -> "브레이크를 밟고 있지 않음"
            Cat.SPEED -> "속도가 ${speedRanges(cs.map { it.v.toInt() }).joinToString(" 또는 ")}가 아님"
            Cat.TEMP -> if (keys.none { it.cat == Cat.TEMP }) "바깥 온도를 알 수 없음"
                else "바깥 온도가 " + cs.sortedBy { it.v }.joinToString(" 또는 ") {
                    when (it.v) { "COLD" -> "10도 이하"; "MILD" -> "10~25도"; else -> "25도 초과" }
                } + "가 아님"
            Cat.STEER -> "핸들이 " + cs.joinToString(" 또는 ") {
                when (it.v) { "STRAIGHT" -> "15도 미만"; "SMALL" -> "15~90도"; else -> "90도 이상" }
            } + "가 아님"
            Cat.WIPER -> "와이퍼가 꺼져 있음"
            Cat.TURN -> "방향지시등이 꺼져 있음"
            Cat.GEAR -> "기어가 ${cs.joinToString(" 또는 ") { it.v }}가 아님"
        }
    }

    /** 자동 포착 규칙을 문장으로. */
    fun ruleSentence(rule: Set<Cond>): String =
        if (rule.isEmpty()) "규칙이 없어요" else "녹음 중에 \"${describe(rule)}\" 조건이 모두 맞기 시작하면 3초 뒤 저장해요"

    /** 지금 자동 포착이 저장할 수 있는 상태인지 한 줄로. 저장 안 되는 이유를 바로 보여 준다. */
    fun statusLine(rule: Set<Cond>, keys: Set<Cond>, recording: Boolean): String {
        val miss = buildList { if (!recording) add("녹음이 꺼져 있음"); addAll(unmet(rule, keys)) }
        return if (miss.isEmpty()) "지금 차 상태: 규칙을 모두 만족해요" else "지금 저장 안 됨: ${miss.joinToString(" · ")}"
    }

    /** 규칙을 한 줄로. 연속된 속도 구간은 "시속 20~40km" 처럼 합친다. */
    fun describe(rule: Set<Cond>): String {
        if (rule.isEmpty()) return "공통 조건 없음"
        return ORDER.flatMap { cat ->
            val cs = rule.filter { it.cat == cat }
            if (cat == Cat.SPEED && cs.isNotEmpty()) speedRanges(cs.map { it.v.toInt() }) else cs.sortedBy { it.v }.map { label(it) }
        }.joinToString(", ")
    }

    private fun speedRanges(bands: List<Int>): List<String> {
        val out = mutableListOf<String>()
        val sorted = bands.sorted()
        var lo = sorted.first(); var hi = lo
        for (b in sorted.drop(1)) {
            if (b == hi + SPEED_BAND_KMH) hi = b else { out += range(lo, hi); lo = b; hi = b }
        }
        out += range(lo, hi)
        return out
    }

    private fun range(lo: Int, hi: Int) = "시속 $lo~${hi + SPEED_BAND_KMH}km"

    /** 재현 레시피: 정비소에서 소리를 다시 들어 보려면 어떻게 달려야 하는지. */
    fun recipe(rule: Set<Cond>): String {
        if (rule.isEmpty()) return "아직 공통 조건이 없어 재현 방법을 만들 수 없어요. 기록을 더 모아 주세요."
        val speed = rule.filter { it.cat == Cat.SPEED }.map { it.v.toInt() }
        val terms = mutableListOf<String>()
        if (rule.any { it.cat == Cat.BRAKE }) terms += "브레이크"
        rule.filter { it.cat == Cat.STEER }.forEach { terms += label(it) }
        if (rule.any { it.cat == Cat.TURN }) terms += "방향지시등"
        if (rule.any { it.cat == Cat.WIPER }) terms += "와이퍼 작동"
        rule.filter { it.cat == Cat.GEAR }.forEach { terms += "기어 ${it.v}" }
        rule.filter { it.cat == Cat.TEMP }.forEach {
            terms += when (it.v) { "COLD" -> "바깥 온도 10도 이하"; "HOT" -> "바깥 온도 25도 초과"; else -> "바깥 온도 10~25도" }
        }
        val head = if (speed.isEmpty()) "" else speedRanges(speed).joinToString(" 또는 ") + "에서" + if (terms.isEmpty()) "" else " "
        return head + terms.joinToString(", ") + (if (terms.isEmpty()) "" else "일 때") + " 들릴 가능성이 높음"
    }

    // ---------------- 규칙 저장 형식 ----------------

    /** "BRAKE=ON|SPEED=20,30|TEMP=COLD" */
    fun encode(rule: Set<Cond>): String =
        rule.groupBy { it.cat }.toSortedMap().entries.joinToString("|") { (cat, cs) -> "${cat.name}=${cs.map { it.v }.sorted().joinToString(",")}" }

    fun decode(s: String?): Set<Cond> {
        if (s.isNullOrBlank()) return emptySet()
        return s.split("|").flatMap { part ->
            val (cat, vs) = part.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val c = runCatching { Cat.valueOf(cat) }.getOrNull() ?: return@flatMap emptyList()
            vs.split(",").filter { it.isNotBlank() }.map { Cond(c, it) }
        }.toSet()
    }
}
