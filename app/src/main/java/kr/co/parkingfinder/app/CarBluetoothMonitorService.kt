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
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CarBluetoothMonitorService : Service() {
    private val channelId = "parking_monitor"
    private var receiverRegistered = false

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
            updateNotification("차량 연결 해제 감지 · 주차 위치 기록 시도")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerBluetoothReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification("차량 블루투스 연결 감지 중"))
        return START_STICKY
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
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            getSharedPreferences("parking_finder", MODE_PRIVATE).edit()
                .putString("monitor_status", "블루투스 연결 해제 감지 · 위치 권한 필요").apply()
            return
        }
        val manager = getSystemService(LocationManager::class.java)
        val candidates = try {
            listOfNotNull(
                manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            )
        } catch (_: SecurityException) { emptyList() }
        val location = candidates.maxByOrNull { it.time }
        val prefs = getSharedPreferences("parking_finder", MODE_PRIVATE)
        if (location == null) {
            prefs.edit().putString("monitor_status", "차량 연결 해제 감지 · 최근 위치 없음").apply()
            return
        }
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(location.time))
        val ageMinutes = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L) / 60000L)
        val status = if (ageMinutes <= 5) "차량 연결 해제 · 주차 위치 저장 완료"
            else "차량 연결 해제 · 마지막 위치 저장 (약 ${ageMinutes}분 전)"
        val text = "위도 %.5f · 경도 %.5f".format(location.latitude, location.longitude)
        prefs.edit().putString("last_location", text).putString("last_time", time)
            .putString("monitor_status", status).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "주차 자동 감지",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "차량 블루투스 연결 상태를 감지합니다."
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
        if (receiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
