package kr.co.parkingfinder.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
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
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import java.io.File
import android.webkit.WebView
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

    private val ticker = object : Runnable {
        override fun run() { est.tick(System.currentTimeMillis()); handler.postDelayed(this, 1000) }
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startLocation() else Toast.makeText(this, "위치 권한이 없으면 지하 진입을 알 수 없어요", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).also { setContentView(it) }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/map.html")
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (baro == null) Toast.makeText(this, "이 기기에는 기압 센서가 없어요. 층은 직접 선택하세요", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        baro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startLocation() else askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this); lm.removeUpdates(this); handler.removeCallbacks(ticker)
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
                    wifi.scanResults.take(40).forEach { result ->
                        wifiFingerprint.put(JSONObject()
                            .put("bssid", result.BSSID ?: "")
                            .put("rssi", result.level)
                            .put("frequency", result.frequency)
                            .put("ageMs", (System.currentTimeMillis() - result.timestamp / 1000L).coerceAtLeast(0L)))
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
                .put("pillarCode", prefs.getString("pending_training_pillar", "") ?: "")
                .put("ocrText", prefs.getString("pending_training_ocr", "") ?: "")
                .put("photoPath", prefs.getString("pending_training_photo", "") ?: "")
                .put("latitude", prefs.getString("latest_latitude", "") ?: "")
                .put("longitude", prefs.getString("latest_longitude", "") ?: "")
                .put("estimatedFloor", prefs.getString("estimated_floor", "") ?: "")
                .put("wifiFingerprint", wifiFingerprint)
                .put("sensorState", est.json())
                .put("source", "user_confirmed_map")
            records.put(learningRecord)
            while (records.length() > 200) {
                val expiredPhoto = records.optJSONObject(0)?.optString("photoPath", "") ?: ""
                if (expiredPhoto.isNotBlank()) runCatching { File(expiredPhoto).delete() }
                records.remove(0)
            }
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
                .remove("pending_training_photo")
                .remove("pending_training_ocr")
                .remove("pending_training_pillar")
                .remove("pending_training_photo_time")
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
