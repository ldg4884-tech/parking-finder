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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        @JavascriptInterface fun lastAuto(): String {
            val p = getSharedPreferences("parking_finder", MODE_PRIVATE)
            return JSONObject().put("loc", p.getString("last_location", "") ?: "")
                .put("time", p.getString("last_time", "") ?: "").toString()
        }
        @JavascriptInterface fun openSettings() {
            runOnUiThread { startActivity(Intent(this@MapActivity, MainActivity::class.java)) }
        }
    }
}
