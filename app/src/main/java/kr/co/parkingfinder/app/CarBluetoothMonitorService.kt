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
            updateNotification("차량 연결 해제 감지 · 최근 수집 위치 기록")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerBluetoothReceiver()
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
