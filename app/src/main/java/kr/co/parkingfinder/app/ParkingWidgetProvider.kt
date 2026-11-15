package kr.co.parkingfinder.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

open class ParkingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(context, manager, it) }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            listOf(ParkingWidgetSmallProvider::class.java, ParkingWidgetLargeProvider::class.java).forEach { cls ->
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                ids.forEach { updateOne(context, manager, it, cls == ParkingWidgetSmallProvider::class.java) }
            }
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, id: Int, small: Boolean? = null) {
            val provider = manager.getAppWidgetInfo(id)?.provider?.className
            val isSmall = small ?: (provider == ParkingWidgetSmallProvider::class.java.name)
            val views = RemoteViews(context.packageName,
                if (isSmall) R.layout.widget_parking_small else R.layout.widget_parking_large)
            val prefs = context.getSharedPreferences("parking_finder", Context.MODE_PRIVATE)
            val location = prefs.getString("last_location", "").orEmpty()
            val time = prefs.getString("last_time", "").orEmpty()
            val saved = location.isNotBlank() && time.isNotBlank()
            views.setTextViewText(R.id.widget_location, if (saved) location else "위치 확인 필요")
            views.setTextViewText(R.id.widget_time,
                if (saved) "저장 시각 · $time" else "주차 후 지도에서 위치를 저장하세요")
            views.setTextViewText(R.id.widget_status,
                if (saved) "최근 저장 위치" else "아직 저장된 주차 위치가 없습니다")
            val intent = Intent(context, MapActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("widget_open_map", true)
            }
            val pending = PendingIntent.getActivity(context, id + 300,
                intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            views.setOnClickPendingIntent(R.id.widget_find, pending)
            if (!isSmall) views.setOnClickPendingIntent(R.id.widget_save, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
class ParkingWidgetSmallProvider : ParkingWidgetProvider()
class ParkingWidgetLargeProvider : ParkingWidgetProvider()
