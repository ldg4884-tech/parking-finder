package kr.co.parkingfinder.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
    var locationText by remember { mutableStateOf("아직 저장된 주차 위치가 없습니다") }
    var savedAt by remember { mutableStateOf("") }
    var hasLocation by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("주차 감지 준비") }

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
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172334)),
                    shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MyLocation, null, tint = Color(0xFF75D5C2))
                            Text("  주차 위치 모니터링", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Surface(color = Color(0xFF25463F), shape = RoundedCornerShape(20.dp)) {
                                Text("준비됨", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = Color(0xFF9CE8D8), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("자동 주차 감지 기능을 준비하고 있습니다.",
                            color = Color(0xFF9BAABD), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            if (fine || coarse) captureLocation(context) { text, time, message ->
                                locationText = text; savedAt = time; hasLocation = time.isNotBlank(); status = message
                            } else locationPermission.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
                            ))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.LocationOn, null); Text("  현재 위치 확인 및 저장")
                        }
                    }
                }
                Text("내 주차 위치", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Card(Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF172334)),
                    shape = RoundedCornerShape(22.dp)) {
                    Box(Modifier.fillMaxSize().padding(12.dp)) {
                        ParkingPlanPreview(Modifier.fillMaxSize())
                        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                            Text("주차장 도면", fontWeight = FontWeight.Bold)
                            Text("도면 등록 후 위치 매핑 지원 예정",
                                color = Color(0xFF9BAABD), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                            Text(if (hasLocation) locationText else "도면을 등록하면 차량 위치가 표시됩니다.",
                                color = Color(0xFF9BAABD), style = MaterialTheme.typography.bodySmall)
                            if (savedAt.isNotBlank()) Text(savedAt, color = Color(0xFF75D5C2),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { status = "도면 등록 기능은 다음 개발 단계에서 연결됩니다." },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null); Text(" 도면 등록")
                    }
                    OutlinedButton(onClick = { status = "주차 기록 기능은 다음 개발 단계에서 연결됩니다." },
                        modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Map, null); Text(" 주차 기록")
                    }
                }
                Text("현재 버전은 화면 및 위치 저장 기반입니다. 실내 주차면 자동 판별은 위치 보정 기술 연동 후 제공됩니다.",
                    color = Color(0xFF8291A5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun captureLocation(context: android.content.Context, result: (String, String, String) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java)
    val location: Location? = try {
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (_: SecurityException) { null }
    if (location != null) {
        val text = "위도 %.5f · 경도 %.5f".format(location.latitude, location.longitude)
        val time = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date())
        result(text, time, "현재 위치 저장 완료")
    } else result("위치 정보를 아직 가져오지 못했습니다.", "", "위치 신호를 기다리는 중")
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
