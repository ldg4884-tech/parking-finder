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
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pairedDevices = getPairedDevices()
            showDevices = true
        } else status = "차량 블루투스 기기 확인 권한이 필요합니다"
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF75D5C2), background = Color(0xFF0D1420),
        surface = Color(0xFF172334), onBackground = Color(0xFFF4F7FA),
        onSurface = Color(0xFFF4F7FA)
    )) {
        Scaffold(containerColor = Color(0xFF0D1420), topBar = {
            TopAppBar(title = {
                Column {
                    Text("PARKING FINDER", fontWeight = FontWeight.Bold)
                    Text("내 차 주차 위치", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9BAABD))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF0D1420), titleContentColor = Color.White
            ))
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172334)),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MyLocation, null, tint = Color(0xFF75D5C2))
                            Text("  주차 위치 모니터링", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Surface(color = Color(0xFF25463F), shape = RoundedCornerShape(20.dp)) {
                                Text("자동 감지", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = Color(0xFF9CE8D8), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (selectedDeviceName.isBlank()) "차량 블루투스를 등록하면 연결 해제 시 주차 위치를 기록합니다."
                            else "감지 차량: $selectedDeviceName",
                            color = Color(0xFF9BAABD), style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 31 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                pairedDevices = getPairedDevices()
                                showDevices = true
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Bluetooth, null)
                            Text("  차량 블루투스 기기 선택")
                        }
                        OutlinedButton(onClick = {
                            val address = prefs.getString("car_bt_address", null)
                            if (address.isNullOrBlank()) {
                                status = "먼저 차량 블루투스 기기를 선택해 주세요."
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else if (Build.VERSION.SDK_INT >= 31 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                ContextCompat.startForegroundService(context, Intent(context, CarBluetoothMonitorService::class.java))
                                status = "차량 블루투스 연결 감지 중"
                                prefs.edit().putString("monitor_status", status).apply()
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("자동 주차 감지 켜기")
                        }
                    }
                }
                Text("최근 주차 위치", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Card(Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF172334)),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            floors.forEach { floor ->
                                FilterChip(selected = selectedFloor == floor, onClick = {
                                    selectedFloor = floor
                                    prefs.edit().putString("selected_floor", floor).apply()
                                }, label = { Text(floor) })
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.fillMaxSize().pointerInput(selectedFloor, floorPlanUri) {
                            detectTapGestures { tap ->
                                markerX = (tap.x / size.width).coerceIn(0f, 1f)
                                markerY = (tap.y / size.height).coerceIn(0f, 1f)
                                prefs.edit().putFloat("marker_x_$selectedFloor", markerX)
                                    .putFloat("marker_y_$selectedFloor", markerY).apply()
                                status = "$selectedFloor 도면에 주차 위치를 표시했습니다."
                            }
                        }) {
                            if (floorPlanImage != null) {
                                Image(bitmap = floorPlanImage!!, contentDescription = "$selectedFloor 주차장 도면",
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                            } else ParkingPlanPreview(Modifier.fillMaxSize())
                            if (markerX >= 0f && markerY >= 0f) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val center = Offset(size.width * markerX, size.height * markerY)
                                    drawCircle(Color(0xFF75D5C2).copy(alpha = 0.28f), 24.dp.toPx(), center)
                                    drawCircle(Color(0xFF75D5C2), 9.dp.toPx(), center)
                                    drawCircle(Color.White, 3.dp.toPx(), center)
                                }
                            }
                        }
                        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                            Text("$selectedFloor 주차장 도면", fontWeight = FontWeight.Bold)
                            Text(
                                if (floorPlanImage != null) "등록된 도면" else "도면을 등록해 주차장을 표시하세요",
                                color = Color(0xFF9BAABD), style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Column(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                            Text(if (markerX >= 0f && markerY >= 0f) "도면에 지정한 위치가 표시되어 있습니다."
                                else "도면을 눌러 주차 위치를 직접 표시할 수 있습니다.",
                                color = Color(0xFF9BAABD), style = MaterialTheme.typography.bodySmall)
                            if (hasLocation) Text(locationText, color = Color(0xFF9BAABD), style = MaterialTheme.typography.labelSmall)
                            if (savedAt.isNotBlank()) Text(savedAt, color = Color(0xFF75D5C2),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text("도면을 누르면 민트색 위치 표시가 저장됩니다. 층별 도면과 위치는 각각 보관됩니다.",
                    color = Color(0xFF8291A5), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { floorPlanPicker.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null); Text(" 도면 등록")
                    }
                    OutlinedButton(onClick = {
                        historyItems = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toList().sortedDescending()
                        showHistory = true
                    },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Map, null); Text(" 주차 기록")
                    }
                }
                Text("차량 블루투스 연결이 끊기면 마지막으로 확인된 위치를 기록합니다. 지하 주차면 단위의 정확한 위치는 실내 측위 연동이 추가로 필요합니다.",
                    color = Color(0xFF8291A5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("최근 주차 기록") },
            text = {
                if (historyItems.isEmpty()) Text("아직 저장된 주차 기록이 없습니다.")
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    historyItems.take(30).forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider(color = Color(0xFF40536A))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("닫기") } }
        )
    }

    if (showDevices) {
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("차량 블루투스 선택") },
            text = {
                if (pairedDevices.isEmpty()) {
                    Text("페어링된 블루투스 기기가 없습니다. 차량과 휴대폰을 먼저 페어링해 주세요.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pairedDevices.forEach { device ->
                            TextButton(onClick = {
                                val name = try { device.name ?: "차량 블루투스" } catch (_: SecurityException) { "차량 블루투스" }
                                prefs.edit().putString("car_bt_address", device.address)
                                    .putString("car_bt_name", name).apply()
                                selectedDeviceName = name
                                showDevices = false
                                status = "차량 기기 등록 완료 · 자동 감지를 켜 주세요"
                            }) {
                                Text(try { device.name ?: "이름 없는 기기" } catch (_: SecurityException) { "블루투스 기기" })
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("닫기") } }
        )
    }
}

private fun getPairedDevices(): List<BluetoothDevice> {
    return try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList()?.sortedBy {
            it.name ?: ""
        } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }
}

private fun captureLocation(context: android.content.Context, result: (String, String, String) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java)
    val candidates = try {
        listOfNotNull(
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        )
    } catch (_: SecurityException) { emptyList() }
    val location = candidates.maxByOrNull { it.time }
    if (location != null) {
        val text = "위도 %.5f · 경도 %.5f".format(location.latitude, location.longitude)
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(location.time))
        result(text, time, "현재 위치 저장 완료")
    } else result("최근 위치 정보가 없습니다.", "", "위치 정보를 가져오지 못했습니다. 위치 권한과 GPS를 확인해 주세요.")
}

@Composable
private fun ParkingPlanPreview(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val line = Color(0xFF40536A)
        for (i in 0..5) {
            val y = h * (0.28f + i * 0.105f)
            drawLine(line, Offset(w * 0.08f, y), Offset(w * 0.92f, y), 2.dp.toPx())
        }
        for (i in 0..7) {
            val x = w * (0.08f + i * 0.12f)
            drawLine(line, Offset(x, h * 0.24f), Offset(x, h * 0.82f), 2.dp.toPx())
        }
        drawRoundRect(Color(0xFF24364A), topLeft = Offset(w * 0.12f, h * 0.38f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.22f),
            style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color(0xFF75D5C2).copy(alpha = 0.18f), w * 0.09f, Offset(w * 0.5f, h * 0.49f))
        drawCircle(Color(0xFF75D5C2), w * 0.035f, Offset(w * 0.5f, h * 0.49f))
    }
}
