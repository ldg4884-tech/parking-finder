package kr.co.parkingfinder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import org.json.JSONArray
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarBluetoothMonitorService : Service(), SensorEventListener {
    private val channelId = "parking_monitor"
    private var receiverRegistered = false
    private var locationUpdatesStarted = false
    private var wifiReceiverRegistered = false
    private var pendingWifiPrediction = false
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private val floorEstimator = FloorEstimator()
    private val floorHandler = Handler(Looper.getMainLooper())
    private val floorTicker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            floorEstimator.tick(now)
            val key = floorEstimator.floorKey
            if (floorEstimator.underground && key != null) {
                val floor = if (key == "c") "B2" else "B1"
                getSharedPreferences("parking_finder", MODE_PRIVATE).edit()
                    .putString("estimated_floor", floor)
                    .putLong("estimated_floor_updated_at", now)
                    .apply()
            }
            floorHandler.postDelayed(this, 1_000L)
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            floorEstimator.onFix(System.currentTimeMillis(), location.accuracy)
            val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
            prefs.edit()
                .putString("latest_latitude", location.latitude.toString())
                .putString("latest_longitude", location.longitude.toString())
                .putLong("latest_location_time", location.time)
                .putFloat("latest_location_accuracy", location.accuracy)
                .apply()
        }
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!pendingWifiPrediction || intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            pendingWifiPrediction = false
            if (!predictAndSaveParkingSpot()) {
                updateNotification("주차 시각 자동 기록 · 주차 칸은 학습 신호 부족으로 확인 필요")
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
            val targetAddress = prefs.getString("car_bt_address", null) ?: return
            if (device.address != targetAddress) return
            saveParkingLocation()
            requestAutomaticSpotPrediction()
            updateNotification("차량 연결 해제 감지 · 주차 칸 자동 추정 중")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerBluetoothReceiver()
        registerWifiScanReceiver()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification("차량 블루투스 연결 감지 중 · 위치 수집 준비"))
        startLocationUpdates()
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        floorHandler.removeCallbacks(floorTicker)
        floorHandler.post(floorTicker)
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (locationUpdatesStarted) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()
                .putString("monitor_status", "위치 권한이 없어 위치 수집을 시작하지 못했습니다").apply()
            return
        }
        val manager = getSystemService(LocationManager::class.java)
        var started = false
        try {
            for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 15_000L, 5f, locationListener, Looper.getMainLooper())
                    started = true
                }
            }
        } catch (_: SecurityException) {
            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()
                .putString("monitor_status", "위치 권한을 확인해 주세요").apply()
        } catch (_: IllegalArgumentException) {
            // 기기에서 지원하지 않는 위치 제공자는 건너뜁니다.
        }
        locationUpdatesStarted = started
        if (started) updateNotification("차량 연결 감지 중 · 최근 위치를 주기적으로 수집")
    }

    private fun registerWifiScanReceiver() {
        if (wifiReceiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wifiScanReceiver, filter)
        }
        wifiReceiverRegistered = true
    }

    private fun requestAutomaticSpotPrediction() {
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            updateNotification("주차 시각 자동 기록 · 위치 권한이 없어 칸 추정은 건너뜀")
            return
        }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            updateNotification("주차 시각 자동 기록 · Wi-Fi가 꺼져 있어 칸 추정은 건너뜀")
            return
        }
        pendingWifiPrediction = true
        val started = runCatching { wifi.startScan() }.getOrDefault(false)
        if (!started) {
            pendingWifiPrediction = false
            if (!predictAndSaveParkingSpot()) {
                updateNotification("주차 시각 자동 기록 · 새 Wi-Fi 검색 제한, 칸은 확인 필요")
            }
        }
    }

    private fun predictAndSaveParkingSpot(): Boolean {
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return false
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val nowElapsed = SystemClock.elapsedRealtime()
        val current = runCatching {
            wifi.scanResults.asSequence()
                .filter { !it.BSSID.isNullOrBlank() && it.timestamp > 0L }
                .filter { (nowElapsed - it.timestamp / 1000L).coerceIn(0L, Long.MAX_VALUE) <= 30_000L }
                .associate { it.BSSID.lowercase() to it.level }
        }.getOrElse { emptyMap() }
        if (current.size < 3) return false

        val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
        val estimated = prefs.getString("estimated_floor", null)
        val estimatedAt = prefs.getLong("estimated_floor_updated_at", 0L)
        val activeFloor = floorEstimator.floorKey ?: if (System.currentTimeMillis() - estimatedAt <= 120_000L) {
            when (estimated) { "B2" -> "c"; "B1" -> "a"; else -> null }
        } else null
        if (activeFloor == null) return false

        val records = runCatching {
            JSONArray(prefs.getString("parking_learning_records", "[]") ?: "[]")
        }.getOrElse { JSONArray() }
        if (records.length() == 0) return false

        data class Candidate(val mapKey: String, val slot: Int, val score: Double, val matched: Int, val samples: Int)
        val groups = mutableMapOf<String, MutableList<Candidate>>()
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
                val age = ap.optLong("ageMs", Long.MAX_VALUE)
                if (bssid.isNotBlank() && age in 0L..30_000L) sample[bssid] = ap.optInt("rssi")
            }
            if (sample.size < 3) continue
            val common = current.keys.intersect(sample.keys)
            if (common.size < 3) continue
            val unionCount = current.keys.union(sample.keys).size.coerceAtLeast(1)
            val overlap = common.size.toDouble() / unionCount
            val meanDifference = common.map { kotlin.math.abs(current.getValue(it) - sample.getValue(it)) }.average()
            val signalSimilarity = (1.0 - meanDifference / 100.0).coerceIn(0.0, 1.0)
            val score = (overlap * 0.60 + signalSimilarity * 0.40).coerceIn(0.0, 1.0)
            groups.getOrPut("$mapKey:$slot") { mutableListOf() }
                .add(Candidate(mapKey, slot, score, common.size, 1))
        }
        val ranked = groups.values.mapNotNull { samples ->
            if (samples.isEmpty()) null else {
                val top = samples.sortedByDescending { it.score }.take(3)
                val first = top.first()
                Candidate(first.mapKey, first.slot, top.map { it.score }.average(),
                    top.minOf { it.matched }, samples.size)
            }
        }.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return false
        val second = ranked.getOrNull(1) ?: return false
        val margin = best.score - second.score
        // 자동 저장은 엄격하게: 같은 칸 기록 3회 이상, 점수 70% 이상, 2순위와 12%p 이상 차이.
        if (best.score < 0.70 || best.matched < 3 || best.samples < 3 || margin < 0.12) return false

        val floorName = if (best.mapKey == "c") "B2" else "B1"
        val mapName = when (best.mapKey) { "a" -> "B1 지도 ①"; "b" -> "B1 지도 ②"; else -> "B2" }
        val spot = "자동 추정: $floorName · $mapName · 지도상 ${best.slot + 1}번 주차면"
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date())
        val history = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toMutableSet()
        history.add("$time  |  $spot  |  차량 블루투스 해제 후 Wi-Fi 자동 추정")
        prefs.edit()
            .putString("last_detected_spot", spot)
            .putString("last_detected_floor", floorName)
            .putString("last_auto_prediction_map_key", best.mapKey)
            .putInt("last_auto_prediction_slot_index", best.slot)
            .putFloat("last_auto_prediction_score", best.score.toFloat())
            .putFloat("last_auto_prediction_margin", margin.toFloat())
            .putString("last_auto_prediction_status", "CONFIDENT")
            .putStringSet("parking_history", history)
            .putString("monitor_status", "$spot · 신뢰도 ${"%.0f".format(best.score * 100)}%")
            .apply()
        updateNotification("$spot · 신뢰도 ${"%.0f".format(best.score * 100)}%")
        return true
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun saveParkingLocation() {
        val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(LocationManager::class.java)
        val candidates = if (fine || coarse) {
            try {
                listOfNotNull(
                    manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                    manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                    manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ).toMutableList()
            } catch (_: SecurityException) { mutableListOf<Location>() }
        } else mutableListOf<Location>()

        val cachedLat = prefs.getString("latest_latitude", null)?.toDoubleOrNull()
        val cachedLon = prefs.getString("latest_longitude", null)?.toDoubleOrNull()
        val cachedTime = prefs.getLong("latest_location_time", 0L)
        if (cachedLat != null && cachedLon != null && cachedTime > 0L) {
            candidates.add(Location(LocationManager.PASSIVE_PROVIDER).apply {
                latitude = cachedLat
                longitude = cachedLon
                time = cachedTime
                accuracy = prefs.getFloat("latest_location_accuracy", 0f)
            })
        }
        val now = System.currentTimeMillis()
        floorEstimator.tick(now)
        val estimatedFloor = if (floorEstimator.underground) {
            floorEstimator.floorKey?.let { if (it == "c") "B2" else "B1" }
        } else null
        val floorText = estimatedFloor?.let { " · $it 층 추정" } ?: " · 층 확인 필요"
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(now))
        val location = candidates.maxByOrNull { it.time }
        val ageMinutes = location?.let { ((now - it.time).coerceAtLeast(0L) / 60000L) }
        val locationUsable = location != null && ageMinutes!! <= 10L
        val accuracyText = if (locationUsable && location!!.hasAccuracy()) " · 정확도 약 ${location.accuracy.toInt()}m" else ""
        val gpsText = if (locationUsable) {
            "GPS 위도 %.5f · 경도 %.5f%s".format(location!!.latitude, location.longitude, accuracyText)
        } else if (location != null) {
            "최근 GPS 정보가 오래됨(약 ${ageMinutes}분 전)"
        } else {
            "GPS 위치 정보 없음"
        }
        val status = if (locationUsable) "주차 시점 자동 기록 완료$floorText · 주차 칸은 별도 인식 필요"
            else "주차 시점 자동 기록 완료$floorText · GPS 위치 없음 · 주차 칸은 별도 인식 필요"
        val spotText = estimatedFloor?.let { "자동 추정 층: $it · 주차 칸은 아직 확인되지 않았습니다" }
            ?: "층·주차 칸 미확인 · 지도에서 확인하거나 기둥 번호를 입력해 주세요"
        val history = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toMutableSet()
        history.add("$time  |  $gpsText  |  차량 블루투스 해제 자동 기록")
        prefs.edit()
            .putString("last_auto_parking_time", time)
            .putString("last_time", time)
            .putString("last_location", "주차 시점 자동 기록됨$floorText · $gpsText")
            .putString("monitor_status", status)
            .putString("last_detected_spot", spotText)
            .putString("last_detected_floor", estimatedFloor)
            .remove("last_detected_zone")
            .remove("last_detected_marker_x")
            .remove("last_detected_marker_y")
            .putStringSet("parking_history", history)
            .apply()
        updateNotification(status)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "주차 자동 감지",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "차량 블루투스 연결과 위치 수집 상태를 감지합니다."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("파킹파인더")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(1001, buildNotification(message))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE && event.values.isNotEmpty()) {
            floorEstimator.onPressure(System.currentTimeMillis(), event.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        floorHandler.removeCallbacks(floorTicker)
        try { sensorManager.unregisterListener(this) } catch (_: UninitializedPropertyAccessException) { }
        if (locationUpdatesStarted) {
            try { getSystemService(LocationManager::class.java).removeUpdates(locationListener) }
            catch (_: SecurityException) { }
            locationUpdatesStarted = false
        }
        if (receiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        if (wifiReceiverRegistered) {
            try { unregisterReceiver(wifiScanReceiver) } catch (_: IllegalArgumentException) { }
            wifiReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
