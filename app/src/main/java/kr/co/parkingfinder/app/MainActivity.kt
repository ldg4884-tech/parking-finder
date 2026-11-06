package kr.co.parkingfinder.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

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
    var manualZoneLabel by remember { mutableStateOf(prefs.getString("last_detected_zone", "") ?: "") }
    var ocrBusy by remember { mutableStateOf(false) }
    var detectedSpotText by remember { mutableStateOf(prefs.getString("last_detected_spot", "아직 비콘으로 확인된 주차 구역이 없습니다") ?: "") }
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationText = prefs.getString("last_location", "아직 저장된 주차 위치가 없습니다") ?: ""
                savedAt = prefs.getString("last_time", "") ?: ""
                hasLocation = savedAt.isNotBlank()
                status = prefs.getString("monitor_status", "주차 감지 준비") ?: "주차 감지 준비"
                selectedDeviceName = prefs.getString("car_bt_name", "") ?: ""
                bleBeaconText = prefs.getString("latest_ble_beacon", "비콘 없이 수동 위치 저장 가능") ?: "비콘 없이 수동 위치 저장 가능"
                detectedSpotText = prefs.getString("last_detected_spot", "아직 저장된 주차 구역이 없습니다") ?: ""
                manualZoneLabel = prefs.getString("last_detected_zone", "") ?: ""
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
    val floors = listOf("B2", "B1")
    var selectedParkingBay by remember(selectedFloor) {
        mutableStateOf(prefs.getString("selected_bay_$selectedFloor", "") ?: "")
    }

    val pillarCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status = "촬영이 취소되었습니다."
        } else {
            ocrBusy = true
            status = "기둥 번호를 읽는 중입니다."
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    val lines: List<String> = visionText.textBlocks.mapNotNull { block -> block.text.trim().takeIf { value -> value.isNotEmpty() } }
                    val candidate = lines.firstOrNull { line ->
                        line.any { it.isDigit() } && line.length <= 30
                    } ?: lines.firstOrNull { it.isNotBlank() }
                    if (candidate.isNullOrBlank()) {
                        status = "번호를 읽지 못했습니다. 밝게 촬영하고 다시 시도해 주세요."
                    } else {
                        manualZoneLabel = candidate
                        status = "인식 결과를 입력란에 넣었습니다. 확인 후 저장해 주세요."
                    }
                    ocrBusy = false
                }
                .addOnFailureListener {
                    status = "문자 인식에 실패했습니다. 다시 촬영해 주세요."
                    ocrBusy = false
                    recognizer.close()
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
        if (grants[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            pairedDevices = getPairedDevices()
            showDevices = true
        } else status = "차량 블루투스 권한이 필요합니다"
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
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("최근 주차 위치", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Card(Modifier.fillMaxWidth(),
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
                        SectionedParkingMap(
                            selectedBay = selectedParkingBay,
                            facilityByZone = listOf("A", "B", "C", "D").associateWith {
                                prefs.getString("facility_${selectedFloor}_$it", "없음") ?: "없음"
                            },
                            pillarsByZone = listOf("A", "B", "C", "D").associateWith {
                                prefs.getString("pillars_${selectedFloor}_$it", "") ?: ""
                            },
                            facilityPositionByZone = listOf("A", "B", "C", "D").associateWith {
                                prefs.getString("facility_position_${selectedFloor}_$it", "중앙 상단") ?: "중앙 상단"
                            },
                            zoneNameByZone = listOf("A", "B", "C", "D").associateWith {
                                prefs.getString("zone_name_${selectedFloor}_$it", "$it 구역") ?: "$it 구역"
                            },
                            bayCountByZone = listOf("A", "B", "C", "D").associateWith {
                                prefs.getInt("bay_count_${selectedFloor}_$it", 6)
                            },
                            onSelect = { bay ->
                                selectedParkingBay = bay
                                prefs.edit().putString("selected_bay_$selectedFloor", bay).apply()
                                status = "$selectedFloor $bay 위치 선택됨"
                            },
                            onFacilityChange = { zone, facility ->
                                prefs.edit().putString("facility_${selectedFloor}_$zone", facility).apply()
                                status = "$zone 구역 시설 위치 저장"
                            },
                            onPillarChange = { zone, pillar ->
                                prefs.edit().putString("pillars_${selectedFloor}_$zone", pillar).apply()
                            },
                            onFacilityPositionChange = { zone, position ->
                                prefs.edit().putString("facility_position_${selectedFloor}_$zone", position).apply()
                                status = "$zone 구역 시설 위치를 $position(으)로 저장"
                            },
                            onZoneNameChange = { zone, name ->
                                prefs.edit().putString("zone_name_${selectedFloor}_$zone", name).apply()
                            },
                            onBayCountChange = { zone, count ->
                                prefs.edit().putInt("bay_count_${selectedFloor}_$zone", count).apply()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (selectedParkingBay.isBlank()) "통로 양쪽의 주차면을 선택하세요."
                            else "선택 위치: $selectedFloor · $selectedParkingBay",
                            color = Color(0xFF75D5C2),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
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
                        Spacer(Modifier.height(8.dp))
                        Text("비콘이나 주차장 도면이 없어도 층과 기둥 번호를 직접 저장할 수 있습니다.",
                            color = Color(0xFF9BAABD), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = manualZoneLabel,
                            onValueChange = { manualZoneLabel = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("주차 위치 / 기둥 번호") },
                            placeholder = { Text("예: 125번 기둥, A구역 12번") }
                        )
                        OutlinedButton(
                            onClick = { pillarCamera.launch(null) },
                            enabled = !ocrBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📷  " + if (ocrBusy) "번호 인식 중..." else "기둥 번호 카메라로 촬영")
                        }
                        Button(
                            onClick = {
                                if (manualZoneLabel.isBlank() && selectedParkingBay.isBlank()) {
                                    status = "기둥 번호를 입력하거나 지도에서 주차면을 선택해 주세요."
                                } else {
                                    val zone = listOf(manualZoneLabel.trim(), selectedParkingBay).filter { it.isNotBlank() }.joinToString(" · ")
                                    val now = System.currentTimeMillis()
                                    val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(now))
                                    val spot = "$selectedFloor $zone"
                                    val history = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toMutableSet()
                                    history.add("$time  |  $spot  |  직접 저장")
                                    prefs.edit()
                                        .putString("last_detected_floor", selectedFloor)
                                        .putString("last_detected_zone", zone)
                                        .putString("last_detected_bay", selectedParkingBay)
                                        .putString("selected_bay_$selectedFloor", selectedParkingBay)
                                        .putString("last_detected_spot", spot)
                                        .putString("last_location", spot)
                                        .putString("last_time", time)
                                        .putString("selected_floor", selectedFloor)
                                        .putString("monitor_status", "$spot 주차 위치 저장 완료")
                                        .putStringSet("parking_history", history)
                                        .apply()
                                    detectedSpotText = "마지막 주차 위치: $spot"
                                    locationText = spot
                                    savedAt = time
                                    hasLocation = true
                                    status = "$spot 위치를 저장했습니다."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("이 주차 위치 저장") }
                        Text(detectedSpotText, color = Color(0xFF9CE8D8), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 31 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
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
                                (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                                bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
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
                Text("통로 양쪽 주차면을 선택하고 기둥 번호를 직접 입력하거나 카메라로 인식해 저장하세요.",
                    color = Color(0xFF8291A5), style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = {
                    historyItems = (prefs.getStringSet("parking_history", emptySet()) ?: emptySet()).toList().sortedDescending()
                    showHistory = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Map, null); Text(" 주차 기록")
                }
                Text("차량 블루투스 연결 해제는 주차 시점을 감지합니다. 주차면은 통로형 지도에서 선택하고 기둥 번호는 직접 입력하거나 OCR로 읽어 저장합니다.",
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


@Composable
private fun SectionedParkingMap(
    selectedBay: String,
    facilityByZone: Map<String, String>,
    pillarsByZone: Map<String, String>,
    facilityPositionByZone: Map<String, String>,
    zoneNameByZone: Map<String, String>,
    bayCountByZone: Map<String, Int>,
    onSelect: (String) -> Unit,
    onFacilityChange: (String, String) -> Unit,
    onPillarChange: (String, String) -> Unit,
    onFacilityPositionChange: (String, String) -> Unit,
    onZoneNameChange: (String, String) -> Unit,
    onBayCountChange: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("구역별 주차 배치도", fontWeight = FontWeight.Bold, color = Color.White)
            Text("편집 가능한 개념도", color = Color(0xFFFFC978),
                style = MaterialTheme.typography.labelSmall)
        }
        Text("시설 종류를 선택한 뒤 6칸 배치도에서 실제 위치를 지정하고 기둥 번호를 입력하세요.",
            color = Color(0xFF9BAABD), style = MaterialTheme.typography.bodySmall)
        listOf("A", "B", "C", "D").forEach { zone ->
            var expanded by remember(zone) { mutableStateOf(false) }
            var countExpanded by remember(zone) { mutableStateOf(false) }
            var zoneName by remember(zone, zoneNameByZone[zone]) {
                mutableStateOf(zoneNameByZone[zone] ?: "$zone 구역")
            }
            var pillarText by remember(zone, pillarsByZone[zone]) {
                mutableStateOf(pillarsByZone[zone] ?: "")
            }
            val bayCount = (bayCountByZone[zone] ?: 6).coerceIn(2, 12)
            val upperCount = bayCount / 2
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1D2D40)),
                shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(zoneName.ifBlank { "$zone 구역" }, color = Color(0xFF75D5C2), fontWeight = FontWeight.Bold)
                        Text("시설: ${facilityByZone[zone] ?: "없음"}",
                            color = Color(0xFFD8E2ED), style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = zoneName,
                        onValueChange = { zoneName = it; onZoneNameChange(zone, it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("구역 이름") },
                        placeholder = { Text("예: 엘리베이터 앞, 출입구 쪽") }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { countExpanded = true }) {
                            Text("주차면 수: $bayCount")
                        }
                        DropdownMenu(expanded = countExpanded, onDismissRequest = { countExpanded = false }) {
                            listOf(2, 4, 6, 8, 10, 12).forEach { count ->
                                DropdownMenuItem(text = { Text("$count면") }, onClick = {
                                    onBayCountChange(zone, count)
                                    countExpanded = false
                                })
                            }
                        }
                        Text("구역별 주차면 수 조정", color = Color(0xFF9BAABD),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (1..upperCount).forEach { number ->
                            ParkingBay("$zone-${number.toString().padStart(2, '0')}",
                                selectedBay, onSelect, Modifier.weight(1f))
                        }
                    }
                    Surface(Modifier.fillMaxWidth().height(28.dp),
                        color = Color(0xFF30445A), shape = RoundedCornerShape(6.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("차량 통로", color = Color(0xFFB6C4D3),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (upperCount + 1..bayCount).forEach { number ->
                            ParkingBay("$zone-${number.toString().padStart(2, '0')}",
                                selectedBay, onSelect, Modifier.weight(1f))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { expanded = true }) { Text("시설 종류 선택") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("없음", "엘리베이터", "출입구", "램프").forEach { facility ->
                                DropdownMenuItem(text = { Text(facility) }, onClick = {
                                    onFacilityChange(zone, facility)
                                    expanded = false
                                })
                            }
                        }
                    }
                    Text("아래 배치도에서 시설이 있는 칸을 선택하세요.",
                        color = Color(0xFF9BAABD), style = MaterialTheme.typography.labelSmall)
                    val positions = listOf("좌측 상단", "중앙 상단", "우측 상단",
                        "좌측 하단", "중앙 하단", "우측 하단")
                    positions.chunked(3).forEach { rowPositions ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            rowPositions.forEach { position ->
                                val active = facilityPositionByZone[zone] == position
                                Surface(
                                    onClick = { onFacilityPositionChange(zone, position) },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    color = if (active) Color(0xFF24564D) else Color(0xFF24364A),
                                    shape = RoundedCornerShape(7.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, if (active) Color(0xFF75D5C2) else Color(0xFF40536A))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            if (active && (facilityByZone[zone] ?: "없음") != "없음")
                                                "◆ ${facilityByZone[zone]}" else position,
                                            color = if (active) Color(0xFF9CE8D8) else Color(0xFFE6EDF5),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = pillarText, onValueChange = {
                        pillarText = it
                        onPillarChange(zone, it)
                    }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("기둥 번호 / 표기") },
                        placeholder = { Text("예: 101, 102, 103") })
                }
            }
        }
        Text("선택된 주차면: ${selectedBay.ifBlank { "없음" }}",
            color = Color(0xFF75D5C2), style = MaterialTheme.typography.bodySmall)
        Text("※ 현재는 임시 구역 배치입니다. 실제 통로·주차면·시설 위치를 확인해 조정해야 합니다.",
            color = Color(0xFFFFC978), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ParkingBay(
    bay: String,
    selectedBay: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = bay == selectedBay
    Surface(
        onClick = { onSelect(bay) },
        modifier = modifier.height(42.dp),
        color = if (selected) Color(0xFF24564D) else Color(0xFF24364A),
        contentColor = if (selected) Color(0xFF9CE8D8) else Color(0xFFE6EDF5),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (selected) Color(0xFF75D5C2) else Color(0xFF40536A)
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(if (selected) "🚗 " else "▱ ", style = MaterialTheme.typography.labelMedium)
            Text(bay, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
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

