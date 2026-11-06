package kr.co.parkingfinder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice\nimport android.bluetooth.BluetoothAdapter\nimport android.bluetooth.le.ScanCallback\nimport android.bluetooth.le.ScanResult\nimport android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarBluetoothMonitorService : Service() {
    private val channelId = "parking_monitor"
    private var receiverRegistered = false
    private var locationUpdatesStarted = false\n    private var bleScanStarted = false\n\n    private val bleScanCallback = object : ScanCallback() {\n        override fun onScanResult(callbackType: Int, result: ScanResult) {\n            val device = result.device\n            val address = try { device.address } catch (_: SecurityException) { return }\n            val name = try { device.name ?: result.scanRecord?.deviceName ?: "이름 없는 비콘" } catch (_: SecurityException) { "BLE 비콘" }\n            val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)\n            val previousRssi = prefs.getInt("latest_ble_rssi", Int.MIN_VALUE)\n            val previousTime = prefs.getLong("latest_ble_time", 0L)\n            val isBetter = result.rssi > previousRssi || System.currentTimeMillis() - previousTime > 15_000L\n            if (isBetter) {\n                prefs.edit().putString("latest_ble_address", address)\n                    .putString("latest_ble_name", name).putInt("latest_ble_rssi", result.rssi)\n                    .putLong("latest_ble_time", System.currentTimeMillis())\n                    .putString("latest_ble_beacon", "실내 비콘: $name · 신호 ${result.rssi} dBm").apply()\n            }\n        }\n        override fun onScanFailed(errorCode: Int) {\n            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()\n                .putString("latest_ble_beacon", "실내 비콘 검색 실패 (코드 $errorCode)").apply()\n        }\n    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification("차량 블루투스 연결 감지 중 · 위치 수집 준비"))
        startLocationUpdates()
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
                    manager.requestLocationUpdates(provider, 60_000L, 15f, locationListener, Looper.getMainLooper())
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

    private fun startBleScanning() {\n        if (bleScanStarted) return\n        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {\n            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()\n                .putString("latest_ble_beacon", "실내 비콘 검색 권한이 필요합니다").apply()\n            return\n        }\n        try {\n            val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return\n            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()\n            scanner.startScan(null, settings, bleScanCallback)\n            bleScanStarted = true\n            updateNotification("차량 연결 감지 중 · GPS 및 실내 BLE 비콘 검색")\n        } catch (_: SecurityException) {\n            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()\n                .putString("latest_ble_beacon", "블루투스 검색 권한을 확인해 주세요").apply()\n        } catch (_: Exception) {\n            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()\n                .putString("latest_ble_beacon", "이 기기에서 BLE 검색을 시작할 수 없습니다").apply()\n        }\n    }\n\n    private fun registerBluetoothReceiver() {
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
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()
                .putString("monitor_status", "블루투스 연결 해제 감지 · 위치 권한 필요").apply()
            return
        }
        val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
        val manager = getSystemService(LocationManager::class.java)
        val candidates = try {
            listOfNotNull(
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ).toMutableList()
        } catch (_: SecurityException) { mutableListOf<Location>() }
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
        val location = candidates.maxByOrNull { it.time }
        if (location == null) {
            prefs.edit().putString("monitor_status", "차량 연결 해제 감지 · 최근 위치 없음").apply()
            return
        }
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(location.time))
        val ageMinutes = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 60000L)
        val accuracyText = if (location.hasAccuracy()) " · 정확도 약 ${location.accuracy.toInt()}m" else ""
        val status = if (ageMinutes <= 2) "차량 연결 해제 · 최근 수집 위치 저장 완료"
            else "차량 연결 해제 · 마지막 위치 저장 (약 ${ageMinutes}분 전)"
        val text = "위도 %.5f · 경도 %.5f%s".format(location.latitude, location.longitude, accuracyText)
        val historyEntry = "$time  |  $text"
        val history = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toMutableSet()
        history.add(historyEntry)
        prefs.edit().putString("last_location", text).putString("last_time", time)
            .putString("monitor_status", status)
            .putStringSet("parking_history", history).apply()
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

    override fun onDestroy() {
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
