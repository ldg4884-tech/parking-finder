package kr.co.parkingfinder.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ParkingFinderApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParkingFinderApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("parking_finder", 0) }
    var locationText by remember { mutableStateOf(prefs.getString("last_location", "아직 저장된 주차 위치가 없습니다") ?: "") }
    var savedAt by remember { mutableStateOf(prefs.getString("last_time", "") ?: "") }
    var hasLocation by remember { mutableStateOf(savedAt.isNotBlank()) }
    var status by remember { mutableStateOf(prefs.getString("monitor_status", "주차 감지 준비") ?: "") }
    var selectedDeviceName by remember { mutableStateOf(prefs.getString("car_bt_name", "") ?: "") }
    var bleBeaconText by remember { mutableStateOf(prefs.getString("latest_ble_beacon", "실내 비콘 신호 대기 중") ?: "실내 비콘 신호 대기 중") }
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationText = prefs.getString("last_location", "아직 저장된 주차 위치가 없습니다") ?: ""
                savedAt = prefs.getString("last_time", "") ?: ""
                hasLocation = savedAt.isNotBlank()
                status = prefs.getString("monitor_status", "주차 감지 준비") ?: "주차 감지 준비"
                selectedDeviceName = prefs.getString("car_bt_name", "") ?: ""
                bleBeaconText = prefs.getString("latest_ble_beacon", "실내 비콘 신호 대기 중") ?: "실내 비콘 신호 대기 중"
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    var showDevices by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var showHistory by remember { mutableStateOf(false) }
    var historyItems by remember { mutableStateOf(emptyList<String>()) }
    var selectedFloor by remember { mutableStateOf(prefs.getString("selected_floor", "B1") ?: "B1") }
    val floors = listOf("B2", "B1", "1F")
    val floorKey = "floor_plan_uri_$selectedFloor"
    var floorPlanUri by remember(selectedFloor) {
        mutableStateOf(prefs.getString(floorKey, if (selectedFloor == "B1") prefs.getString("floor_plan_uri", null) else null))
    }
    var markerX by remember(selectedFloor) { mutableFloatStateOf(prefs.getFloat("marker_x_$selectedFloor", -1f)) }
    var markerY by remember(selectedFloor) { mutableFloatStateOf(prefs.getFloat("marker_y_$selectedFloor", -1f)) }
    var floorPlanImage by remember(floorPlanUri) {
        mutableStateOf(
            try {
                floorPlanUri?.let { savedUri ->
                    context.contentResolver.openInputStream(Uri.parse(savedUri))?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }
            } catch (_: Exception) { null }
        )
    }
    val floorPlanPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                floorPlanUri = uri.toString()
                prefs.edit().putString(floorKey, floorPlanUri).putString("selected_floor", selectedFloor).apply()
                status = "$selectedFloor 도면 등록 완료"
            } catch (_: Exception) {
                status = "도면을 저장하지 못했습니다. 다시 선택해 주세요."
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            captureLocation(context) { text, time, message ->
                locationText = text; savedAt = time; hasLocation = time.isNotBlank(); status = message
            }
        } else status = "위치 권한이 필요합니다"
    }

    val bluetoothPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            grants[Manifest.permission.BLUETOOTH_SCAN] == true) {
            pairedDevices = getPairedDevices()
            showDevices = true
        } else status = "차량 블루투스 및 실내 비콘 검색 권한이 필요합니다"
    }
