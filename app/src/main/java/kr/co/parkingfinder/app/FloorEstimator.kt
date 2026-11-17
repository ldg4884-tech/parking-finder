package kr.co.parkingfinder.app

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** 도면(건축물현황도)에 적힌 층별 바닥 높이(EL, m). 현장 확인 후 수정하세요. */
data class FloorDef(val key: String, val name: String, val elM: Double)

/**
 * GPS가 끊기는 순간(=지하 진입)의 기압을 기준으로, 이후 기압 변화를 높이 변화로 바꿔
 * 도면의 층별 바닥 높이 중 가장 가까운 층을 고른다.
 * entryElM: 주차장 진입 전 기준 바닥 높이 가정값. 기본 31.5 (1층 데크 출입구 기준 가정).
 */
class FloorEstimator(
    private val floors: List<FloorDef> = DEFAULT_FLOORS,
    var entryElM: Double = 31.5
) {
    companion object {
        val DEFAULT_FLOORS = listOf(
            FloorDef("a", "지하2층", 21.5),
            FloorDef("b", "지상1층", 31.5),
            FloorDef("c", "지하1층", 26.7)
        )
        const val GOOD_ACCURACY_M = 25f
        const val LOST_AFTER_MS = 8_000L
        const val MAX_MATCH_M = 2.5
        const val HOLD_MS = 3_000L
    }

    private val buf = ArrayDeque<Pair<Long, Double>>()
    private var lastGoodFix = 0L
    private var baseline: Double? = null
    private var calOffset = 0.0
    private var candidate: String? = null
    private var candSince = 0L

    var floorKey: String? = null; private set
    var relAltM = 0.0; private set
    var underground = false; private set
    var pressure = 0.0; private set

    @Synchronized fun onPressure(t: Long, hpa: Double) {
        pressure = hpa
        buf.addLast(t to hpa)
        while (buf.isNotEmpty() && t - buf.first().first > 10 * 60_000) buf.removeFirst()
    }

    @Synchronized fun onFix(t: Long, accuracyM: Float) {
        if (accuracyM > GOOD_ACCURACY_M) return
        lastGoodFix = t
        if (underground) {
            underground = false; baseline = null; floorKey = null; candidate = null; calOffset = 0.0
        }
    }

    @Synchronized fun tick(t: Long) {
        if (!underground && lastGoodFix > 0 && t - lastGoodFix > LOST_AFTER_MS) {
            val w = buf.filter { it.first in (lastGoodFix - 60_000)..lastGoodFix }.map { it.second }.sorted()
            if (w.isNotEmpty()) { baseline = w[w.size / 2]; underground = true; calOffset = 0.0 }
        }
        val b = baseline
        if (!underground || b == null) return
        val recent = buf.filter { t - it.first <= 5_000 }.map { it.second }
        if (recent.isEmpty()) return
        relAltM = 44330.0 * (1.0 - (recent.average() / b).pow(1.0 / 5.255))
        val est = entryElM + relAltM + calOffset
        val best = floors.minByOrNull { abs(it.elM - est) } ?: return
        val match = if (abs(best.elM - est) <= MAX_MATCH_M) best.key else null
        if (match != candidate) { candidate = match; candSince = t }
        if (t - candSince >= HOLD_MS) floorKey = candidate
    }

    @Synchronized fun calibrate(key: String) {
        val f = floors.firstOrNull { it.key == key } ?: return
        if (baseline == null) {
            val recent = buf.takeLast(20).map { it.second }
            if (recent.isEmpty()) return
            baseline = recent.average(); underground = true; entryElM = f.elM; calOffset = 0.0; relAltM = 0.0
        } else {
            calOffset = f.elM - entryElM - relAltM
        }
        floorKey = key; candidate = key; candSince = 0L
    }

    @Synchronized fun json(): String = String.format(
        Locale.US, "{\"mode\":\"%s\",\"floor\":%s,\"rel\":%.1f,\"hpa\":%.1f}",
        if (underground) "underground" else "ground",
        floorKey?.let { "\"$it\"" } ?: "null", relAltM, pressure
    )
}
