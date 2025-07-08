package lab.rreedd.oriens

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "OriensWidget"
private const val PREFS_NAME = "lab.rreedd.oriens.OriensWidgetPrefs"
private const val PREF_LATITUDE_PREFIX = "latitude_"
private const val PREF_LONGITUDE_PREFIX = "longitude_"
private const val ACTION_WIDGET_CLICK_UPDATE = "lab.rreedd.oriens.ACTION_WIDGET_CLICK_UPDATE"
private const val DEFAULT_LATITUDE = 41.89862723117168 // Default latitude Pantheon - When cannot get location
private const val DEFAULT_LONGITUDE = 12.476861603108699 // Default longitude Pantheon

class Oriens : AppWidgetProvider() {

    // Roman time labels
    private val horaDayLabels = arrayOf(
        "Hōra Diēī Prīma", "Hōra Diēī Secunda", "Hōra Diēī Tertia", "Hōra Diēī Quārta",
        "Hōra Diēī Quīnta", "Hōra Diēī Sexta", "Hōra Diēī Septima", "Hōra Diēī Octāva",
        "Hōra Diēī Nōna", "Hōra Diēī Decima", "Hōra Diēī Ūndecima", "Hōra Diēī Duodecima"
    )
    private val horaNightLabels = arrayOf(
        "Hōra Noctis Prīma", "Hōra Noctis Secunda", "Hōra Noctis Tertia", "Hōra Noctis Quārta",
        "Hōra Noctis Quīnta", "Hōra Noctis Sexta", "Hōra Noctis Septima", "Hōra Noctis Octāva",
        "Hōra Noctis Nōna", "Hōra Noctis Decima", "Hōra Noctis Ūndecima", "Hōra Noctis Duodecima"
    )
    private val vigiliaLabels = arrayOf(
        "Prīma Vigilia", "Secunda Vigilia", "Tertia Vigilia", "Quārta Vigilia"
    )

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ids: ${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { appWidgetId ->
            val today = Calendar.getInstance()
            updateAppWidgetInternal(today, context, appWidgetManager, appWidgetId, forceRecalc = false)
            SunriseWidgetAlarmUtils.scheduleNextUpdate(today, context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled called")
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        val today = Calendar.getInstance()
        appWidgetIds.forEach { appWidgetId ->
            proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
        }
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "Last widget disabled. Cancelling all alarms.")
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)
        appWidgetIds.forEach { appWidgetId ->
            SunriseWidgetAlarmUtils.cancelAlarm(context, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Log.d(TAG, "onDeleted called for ids: ${appWidgetIds.joinToString()}")
        appWidgetIds.forEach { appWidgetId ->
            SunriseWidgetAlarmUtils.cancelAlarm(context, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Let the base class handle standard actions.

        val action = intent.action
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val today = Calendar.getInstance()

        Log.d(TAG, "onReceive: action = $action, appWidgetId = $appWidgetId")

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisAppWidget = ComponentName(context.packageName, javaClass.name)
        val allWidgetIds = appWidgetManager.getAppWidgetIds(thisAppWidget)

        when (action) {
            // Handle updates from our custom alarm or other system events
            SunriseWidgetAlarmUtils.ACTION_ALARM_UPDATE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            LocationInputActivity.ACTION_LOCATION_UPDATED,
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Received action '$action', updating widgets.")
                val forceRecalc = action != SunriseWidgetAlarmUtils.ACTION_ALARM_UPDATE
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceRecalc)
                } else {
                    allWidgetIds.forEach { id ->
                        proceedWithWidgetUpdate(today, context, id, forceRecalc)
                    }
                }
            }
            // Handle our custom click-to-update action
            ACTION_WIDGET_CLICK_UPDATE -> {
                Log.d(TAG, "Received tap on widget, forcing update.")
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    proceedWithWidgetUpdate(today, context, appWidgetId, forceSunriseRecalc = true)
                }
            }
        }
    }

    private fun updateAppWidgetInternal(
        pseudToday: Calendar,
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        forceRecalc: Boolean
    ) {
        Log.d(TAG, "Updating widget ID: $appWidgetId, forceRecalc: $forceRecalc")
        val views = RemoteViews(context.packageName, R.layout.widget_oriens)

        val (latitude, longitude) = SunriseWidgetAlarmUtils.getCoordinates(context, appWidgetId)
        Log.d(TAG, "Using location for widget $appWidgetId: Lat=$latitude, Lon=$longitude.")

        // Get Gregorian date
        val gregorianDate = SimpleDateFormat("yyyy MM dd", Locale.ITALY).format(pseudToday.time)

        // Get sunrise and sunset times
        val todaySunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(
            context, appWidgetId, latitude, longitude, pseudToday, isSunrise = true, forceRecalc
        )
        var todaySunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(
            context, appWidgetId, latitude, longitude, pseudToday, isSunrise = false, forceRecalc
        )

        if (todaySunriseTime != null && todaySunsetTime != null && todaySunsetTime.timeInMillis < todaySunriseTime.timeInMillis) {
            todaySunsetTime = (todaySunsetTime.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        }

        val romanTimeText: String
        val vigiliaText: String
        val sunTimeText: String

        if (todaySunriseTime != null && todaySunsetTime != null) {
            val (romanTime, vigilia, sunInfo) = calculateRomanTimeAndVigilia(Calendar.getInstance(), todaySunriseTime, todaySunsetTime)
            romanTimeText = romanTime
            vigiliaText = vigilia
            sunTimeText = sunInfo
            Log.d(TAG, "Widget $appWidgetId: $romanTimeText, $vigiliaText (sunInfo: $sunInfo)")
        } else {
            romanTimeText = context.getString(R.string.location_not_set_tap_to_set)
            vigiliaText = "---"
            sunTimeText = context.getString(R.string.fetching_location)
        }

        // Set pending intent to update widget on any click (root layout and roman time text)
        val selfUpdateIntent = Intent(context, Oriens::class.java).apply {
            action = ACTION_WIDGET_CLICK_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("oriens://widget/click/$appWidgetId")
        }
        val selfUpdatePendingIntent = PendingIntent.getBroadcast(
            context, appWidgetId, selfUpdateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        // Set the same update action for both root layout and roman time text
        views.setOnClickPendingIntent(R.id.widget_root_layout, selfUpdatePendingIntent)
        views.setOnClickPendingIntent(R.id.text_roman_time, selfUpdatePendingIntent)

        // Update views
        views.setTextViewText(R.id.text_vigilia, vigiliaText)
        views.setTextViewText(R.id.text_gregorian_date, gregorianDate)
        views.setTextViewText(R.id.text_roman_time, romanTimeText)
        views.setTextViewText(R.id.text_sun_time, sunTimeText)

        try {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget $appWidgetId view updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget view for ID $appWidgetId", e)
        }
    }

    private fun calculateRomanTimeAndVigilia(
        now: Calendar,
        sunriseTime: Calendar,
        sunsetTime: Calendar
    ): Triple<String, String, String> {
        val currentTimeMillis = now.timeInMillis
        val sunriseMillis = sunriseTime.timeInMillis
        val sunsetMillis = sunsetTime.timeInMillis

        val sunriseStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(sunriseTime.time)
        val sunsetStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(sunsetTime.time)
        val sunInfo = "ortus sōlis $sunriseStr\noccāsus sōlis $sunsetStr"

        // Next day's sunrise for night duration calculation
        val nextDaySunriseTime = (sunriseTime.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val nextSunriseMillis = nextDaySunriseTime.timeInMillis

        val nighttimeDurationMillis = nextSunriseMillis - sunsetMillis
        val daytimeDurationMillis = sunsetMillis - sunriseMillis

        if (daytimeDurationMillis <= 0 || nighttimeDurationMillis <= 0) {
            Log.e(TAG, "Duration for time calculation is invalid.")
            return Triple("Calculation Error", "---", sunInfo)
        }

        val romanTime: String
        val vigilia: String

        val isDaytime = currentTimeMillis in sunriseMillis until sunsetMillis

        if (isDaytime) {
            // --- Daytime ---
            val timePassedMillis = currentTimeMillis - sunriseMillis
            val timeUnitMillis = daytimeDurationMillis / 12.0
            val timeUnitIndex = (timePassedMillis / timeUnitMillis).toInt().coerceIn(0, 11)

            romanTime = horaDayLabels[timeUnitIndex]
            vigilia = "Dies (Day)"

        } else {
            // --- Nighttime ---
            val effectiveStartMillis: Long
            // Determine if we are before today's sunrise or after today's sunset
            if (currentTimeMillis < sunriseMillis) { // Before sunrise (e.g., 03:00)
                val previousDaySunset = (sunsetTime.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                effectiveStartMillis = previousDaySunset.timeInMillis
            } else { // After sunset (e.g., 21:00)
                effectiveStartMillis = sunsetMillis
            }

            val nightDurationForCalc = sunriseMillis - effectiveStartMillis
            val timePassedMillis = currentTimeMillis - effectiveStartMillis

            // Calculate Roman Hour (1/12th of night)
            val romanTimeUnitMillis = nighttimeDurationMillis / 12.0
            val romanTimeUnitIndex = (timePassedMillis / romanTimeUnitMillis).toInt().coerceIn(0, 11)
            romanTime = horaNightLabels[romanTimeUnitIndex]

            // Calculate Vigilia (1/4th of night)
            val vigiliaTimeUnitMillis = nighttimeDurationMillis / 4.0
            val vigiliaTimeUnitIndex = (timePassedMillis / vigiliaTimeUnitMillis).toInt().coerceIn(0, 3)
            vigilia = vigiliaLabels[vigiliaTimeUnitIndex]
        }

        return Triple(romanTime, vigilia, sunInfo)
    }

    fun proceedWithWidgetUpdate(
        pseudToday: Calendar,
        context: Context,
        appWidgetId: Int,
        forceSunriseRecalc: Boolean
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateAppWidgetInternal(pseudToday, context, appWidgetManager, appWidgetId, forceSunriseRecalc)
        SunriseWidgetAlarmUtils.scheduleNextUpdate(pseudToday, context, appWidgetId)
    }
}

class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_ON) {
            Log.i(
                "ScreenOnReceiver",
                "Screen ON detected. Triggering widget update via AppWidgetProvider (ACTION_APPWIDGET_UPDATE)."
            )

            val appWidgetManager = AppWidgetManager.getInstance(context)
            // Oyatsu::class.java は、実際の AppWidgetProvider のクラス名に置き換えてください
            val componentName = ComponentName(context, Oriens::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (appWidgetIds == null || appWidgetIds.isEmpty()) {
                Log.d("ScreenOnReceiver", "No widget IDs found for ${componentName.className}")
                return
            }

            // AppWidgetProvider (Oyatsu) に標準の更新インテントを送信します。
            // これにより、Oyatsu の onReceive がトリガーされ、既存のロジックで
            // proceedWithWidgetUpdate が forceSunriseRecalc = true で呼び出されます。
            val updateIntent = Intent(context, Oriens::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(updateIntent)
            Log.d(
                "ScreenOnReceiver",
                "Sent ACTION_APPWIDGET_UPDATE for IDs: ${appWidgetIds.joinToString()}"
            )
        }
    }
}