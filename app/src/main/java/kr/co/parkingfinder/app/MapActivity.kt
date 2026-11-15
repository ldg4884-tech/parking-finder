package kr.co.parkingfinder.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.view.ViewGroup
import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

/** 도면 지도 화면: 층별 주차 칸 지도 + 기압/GPS 기반 지하 층 추정. 기존 MainActivity(블루투스 감지, 기록)는 그대로 둔다. */
class MapActivity : ComponentActivity(), SensorEventListener, LocationListener {
    private lateinit var web: WebView
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private val est = FloorEstimator()
    private val handler = Handler(Looper.getMainLooper())
    private var baro: Sensor? = null
    private var wifiReceiverRegistered = false
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            web.post { web.evaluateJavascript("window.onWifiScanReady && window.onWifiScanReady($updated)", null) }
        }
    }

    private fun registerWifiReceiver() {
        if (wifiReceiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(wifiScanReceiver, filter)
        wifiReceiverRegistered = true
    }

    private val ticker = object : Runnable {
        override fun run() { est.tick(System.currentTimeMillis()); handler.postDelayed(this, 1000) }
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startLocation() else Toast.makeText(this, "위치 권한이 없으면 지하 진입을 알 수 없어요", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).also {
            it.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            it.setBackgroundColor(Color.WHITE)
            setContentView(it)
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        registerWifiReceiver()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val message = "지도 파일을 불러오지 못했습니다. 앱을 다시 실행해 주세요."
                    view.loadDataWithBaseURL(
                        null,
                        "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body style='font-family:sans-serif;padding:24px;background:#101827;color:white'><h2>지도를 불러오지 못했어요</h2><p>$message</p><button onclick='location.reload()' style='padding:14px'>다시 시도</button></body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.e(
                        "ParkingFinderMap",
                        "${message.message()} (line ${message.lineNumber()})"
                    )
                }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/map.html")
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (baro == null) Toast.makeText(this, "이 기기에는 기압 센서가 없어요. 층은 직접 선택하세요", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        registerWifiReceiver()
        baro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startLocation() else askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this); lm.removeUpdates(this); handler.removeCallbacks(ticker)
        if (wifiReceiverRegistered) {
            runCatching { unregisterReceiver(wifiScanReceiver) }
            wifiReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
    }

    override fun onSensorChanged(e: SensorEvent) { est.onPressure(System.currentTimeMillis(), e.values[0].toDouble()) }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onLocationChanged(l: Location) { est.onFix(System.currentTimeMillis(), l.accuracy) }
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("API 29 이하 호환용") override fun onStatusChanged(p: String?, s: Int, x: Bundle?) {}

    inner class Bridge {
        @JavascriptInterface fun requestWifiScan(): Boolean {
            val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation) return false
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) return false
            return runCatching { wifi.startScan() }.getOrDefault(false)
        }
        @JavascriptInterface fun predictParking(): String {
            val result = JSONObject()
            val permission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!permission) return result.put("ok", false).put("reason", "location_permission").toString()

            val nowElapsed = SystemClock.elapsedRealtime()
            val current = runCatching {
                val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                wifi.scanResults.asSequence()
                    .filter { !it.BSSID.isNullOrBlank() && it.timestamp > 0L }
                    .filter { (nowElapsed - it.timestamp / 1000L).coerceIn(0L, Long.MAX_VALUE) <= 30_000L }
                    .associate { it.BSSID.lowercase() to it.level }
            }.getOrElse { emptyMap() }
            if (current.size < 3) return result.put("ok", false)
                .put("reason", "few_signals").put("signalCount", current.size).toString()

            val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
            val records = runCatching {
                JSONArray(prefs.getString("parking_learning_records", "[]") ?: "[]")
            }.getOrElse { JSONArray() }
            if (records.length() == 0) return result.put("ok", false).put("reason", "no_records").toString()

            val activeFloor = est.floorKey
            data class Sample(val mapKey: String, val slot: Int, val score: Double, val matched: Int)
            val scores = mutableMapOf<String, MutableList<Sample>>()
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                val mapKey = record.optString("mapKey")
                if (mapKey !in setOf("a", "b", "c")) continue
                if (activeFloor == "c" && mapKey != "c") continue
                if (activeFloor in setOf("a", "b") && mapKey !in setOf("a", "b")) continue
                val slot = record.optInt("slotIndex", -1)
                if (slot < 0) continue
                val aps = record.optJSONArray("wifiFingerprint") ?: continue
                val sample = mutableMapOf<String, Int>()
                for (j in 0 until aps.length()) {
                    val ap = aps.optJSONObject(j) ?: continue
                    val bssid = ap.optString("bssid").lowercase()
                    val ageMs = ap.optLong("ageMs", Long.MAX_VALUE)
                    if (bssid.isNotBlank() && ageMs in 0L..30_000L) sample[bssid] = ap.optInt("rssi")
                }
                if (sample.size < 3) continue
                val common = current.keys.intersect(sample.keys)
                if (common.size < 3) continue
                val unionCount = current.keys.union(sample.keys).size.coerceAtLeast(1)
                val overlap = common.size.toDouble() / unionCount
                val meanDifference = common.map { kotlin.math.abs(current.getValue(it) - sample.getValue(it)) }.average()
                val signalSimilarity = (1.0 - meanDifference / 100.0).coerceIn(0.0, 1.0)
                val score = (overlap * 0.60 + signalSimilarity * 0.40).coerceIn(0.0, 1.0)
                val key = "$mapKey:$slot"
                scores.getOrPut(key) { mutableListOf() }.add(Sample(mapKey, slot, score, common.size))
            }
            val ranked = scores.values.mapNotNull { samples ->
                if (samples.isEmpty()) null else {
                    val top = samples.sortedByDescending { it.score }.take(3)
                    val first = top.first()
                    val average = top.map { it.score }.average()
                    Sample(first.mapKey, first.slot, average, top.minOf { it.matched }) to samples.size
                }
            }.sortedByDescending { it.first.score }
            val best = ranked.firstOrNull()
            if (best == null) return result.put("ok", false).put("reason", "no_match").toString()
            val candidate = best.first
            val runnerUpScore = ranked.getOrNull(1)?.first?.score ?: 0.0
            val margin = (candidate.score - runnerUpScore).coerceAtLeast(0.0)
            val confident = candidate.score >= 0.65 && candidate.matched >= 3 && best.second >= 2 && margin >= 0.08
            return result.put("ok", true)
                .put("mapKey", candidate.mapKey)
                .put("slotIndex", candidate.slot)
                .put("score", candidate.score)
                .put("matchedAps", candidate.matched)
                 .put("samples", best.second)
                .put("runnerUpScore", runnerUpScore)
                .put("margin", margin)
                .put("confident", confident)
                .put("signalCount", current.size)
                .put("floorKey", activeFloor ?: JSONObject.NULL)
                .toString()
        }
        @JavascriptInterface fun learningStats(): String {
            val records = runCatching {
                JSONArray(getSharedPreferences("parking_finder", MODE_PRIVATE)
                    .getString("parking_learning_records", "[]") ?: "[]")
            }.getOrElse { JSONArray() }
            val result = JSONObject()
            val maps = JSONObject()
            for (key in listOf("a", "b", "c")) {
                var total = 0
                var withWifi = 0
                for (i in 0 until records.length()) {
                    val record = records.optJSONObject(i) ?: continue
                    if (record.optString("mapKey") != key) continue
                    total++
                    if ((record.optJSONArray("wifiFingerprint")?.length() ?: 0) >= 3) withWifi++
                }
                maps.put(key, JSONObject().put("total", total).put("withWifi", withWifi))
            }
            return result.put("total", records.length()).put("maps", maps).toString()
        }
        @JavascriptInterface fun state(): String = est.json()
        @JavascriptInterface fun calibrate(key: String) { est.calibrate(key) }
        @JavascriptInterface fun hasBaro(): Boolean = baro != null
        @JavascriptInterface fun saveManual(floorKey: String, slotIndex: Int, core: String, timestamp: Long, mapX: Double, mapY: Double) {
            if (floorKey !in setOf("a", "b", "c") || slotIndex < 0 || core.isBlank()) return
            val floorName = when (floorKey) {
                "a", "b" -> "지하1층"
                else -> "지하2층"
            }
            val time = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.KOREA)
                .format(java.util.Date(timestamp))
            val mapPart = if (floorKey == "a") "지도 자료 ①" else if (floorKey == "b") "지도 자료 ②" else ""
            val spot = listOf(floorName, mapPart, "지도 기준점 $core 인근", "지도상 ${slotIndex + 1}번 주차면").filter { it.isNotBlank() }.joinToString(" · ")
            val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
            val history = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toMutableSet()
            history.add("$time  |  $spot  |  지도에서 직접 저장")
            val records = JSONArray(prefs.getString("parking_learning_records", "[]") ?: "[]")
            val wifiFingerprint = JSONArray()
            val hasLocationPermission =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasLocationPermission) {
                runCatching {
                    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    val nowElapsed = SystemClock.elapsedRealtime()
                    wifi.scanResults.filter { result -> result.timestamp > 0L && (nowElapsed - result.timestamp / 1000L).coerceIn(0L, Long.MAX_VALUE) <= 30_000L }.take(40).forEach { result ->
                        wifiFingerprint.put(JSONObject()
                            .put("bssid", result.BSSID ?: "")
                            .put("rssi", result.level)
                            .put("frequency", result.frequency)
                            .put("ageMs", (SystemClock.elapsedRealtime() - result.timestamp / 1000L).coerceAtLeast(0L)))
                    }
                }
            }
            val learningRecord = JSONObject()
                .put("timestamp", timestamp)
                .put("floorKey", floorKey)
                .put("mapKey", floorKey)
                .put("slotIndex", slotIndex)
                .put("core", core)
                .put("mapX", mapX)
                .put("mapY", mapY)
                .put("latitude", prefs.getString("latest_latitude", "") ?: "")
                .put("longitude", prefs.getString("latest_longitude", "") ?: "")
                .put("estimatedFloor", prefs.getString("estimated_floor", "") ?: "")
                .put("estimatedFloorUpdatedAt", prefs.getLong("estimated_floor_updated_at", 0L))
                .put("locationTime", prefs.getLong("latest_location_time", 0L))
                .put("locationAccuracy", prefs.getFloat("latest_location_accuracy", -1f).toDouble())
                .put("wifiFingerprint", wifiFingerprint)
                .put("wifiCount", wifiFingerprint.length())
                .put("sensorState", est.json())
                .put("source", "user_confirmed_map")
            records.put(learningRecord)
            while (records.length() > 200) records.remove(0)
            prefs.edit()
                .putString("map_saved_floor_key", floorKey)
                .putInt("map_saved_slot_index", slotIndex)
                .putString("map_saved_core", core)
                .putLong("map_saved_timestamp", timestamp)
                .putString("last_location", spot)
                .putString("last_time", time)
                .putString("last_detected_spot", "마지막 지도 저장 위치: $spot")
                .putString("monitor_status", "$spot 위치 저장 완료 · 학습 기록 " + records.length() + "건")
                .putString("parking_learning_records", records.toString())
                .putInt("parking_learning_record_count", records.length())
                .putStringSet("parking_history", history)
                .apply()
        }
        @JavascriptInterface fun clearManual() {
            val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
            prefs.edit().remove("map_saved_floor_key").remove("map_saved_slot_index")
                .remove("map_saved_core").remove("map_saved_timestamp").apply()
        }
        @JavascriptInterface fun lastAuto(): String {
            val p = getSharedPreferences("parking_finder", MODE_PRIVATE)
            return JSONObject()
                .put("loc", p.getString("last_location", "") ?: "")
                .put("time", p.getString("last_auto_parking_time", "") ?: "")
                .put("floor", p.getString("last_detected_floor", "") ?: "")
                .toString()
        }
        @JavascriptInterface fun openSettings() {
            runOnUiThread { startActivity(Intent(this@MapActivity, MainActivity::class.java)) }
        }
    }
}
