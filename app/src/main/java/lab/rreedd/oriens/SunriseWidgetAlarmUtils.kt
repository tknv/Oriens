package lab.rreedd.oriens

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.edit
import androidx.work.*
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator
import com.luckycatlabs.sunrisesunset.dto.Location
import lab.rreedd.oriens.SunriseWidgetAlarmUtils.ACTION_ALARM_UPDATE
//import lab.rreedd.oriens.SunriseWidgetAlarmUtils.formatMillisToMMSS
//import lab.rreedd.oriens.SunriseWidgetAlarmUtils.formatTimestampToHHMMSS
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

private const val TAG = "OriensWidgetUtils"
// This object remains largely the same, but the update calculation logic is simplified.
object SunriseWidgetAlarmUtils {
    const val PREFS_NAME = "lab.rreedd.oriens.OriensWidgetPrefs"
    const val ACTION_ALARM_UPDATE = "lab.rreedd.oriens.ALARM_UPDATE"
    const val PREF_SUNRISE_TIME_PREFIX = "sunrise_time_"
    const val PREF_SUNSET_TIME_PREFIX = "sunset_time_"
    const val PREF_LATITUDE_PREFIX = "latitude_"
    const val PREF_LONGITUDE_PREFIX = "longitude_"
    const val PREF_LAST_CALC_DATE_PREFIX = "last_calc_date_"

    const val DEFAULT_LATITUDE = 41.89862723117168
    const val DEFAULT_LONGITUDE = 12.476861603108699

    fun getCoordinates(context: Context, appWidgetId: Int): Pair<Double, Double> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latitudeString = prefs.getString(PREF_LATITUDE_PREFIX + appWidgetId, DEFAULT_LATITUDE.toString())
        val longitudeString = prefs.getString(PREF_LONGITUDE_PREFIX + appWidgetId, DEFAULT_LONGITUDE.toString())
        val latitude = latitudeString?.toDoubleOrNull() ?: DEFAULT_LATITUDE
        val longitude = longitudeString?.toDoubleOrNull() ?: DEFAULT_LONGITUDE
        return latitude to longitude
    }

    // getSunriseSunsetTime remains the same as provided.
    fun getSunriseSunsetTime(
        context: Context, appWidgetId: Int, latitude: Double, longitude: Double,
        targetDate: Calendar, isSunrise: Boolean, forceRecalc: Boolean
    ): Calendar? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayDateString = dateFormat.format(targetDate.time)
        val storedTimeKey = (if (isSunrise) PREF_SUNRISE_TIME_PREFIX else PREF_SUNSET_TIME_PREFIX) + appWidgetId
        val storedDateKey = PREF_LAST_CALC_DATE_PREFIX + appWidgetId
        val storedMillis = prefs.getLong(storedTimeKey, -1L)
        val lastCalcDate = prefs.getString(storedDateKey, null)

        if (!forceRecalc && storedMillis != -1L && lastCalcDate == todayDateString) {
            return Calendar.getInstance().apply { timeInMillis = storedMillis }
        }

        try {
            val location = Location(latitude, longitude)
            val calculator = SunriseSunsetCalculator(location, targetDate.timeZone.id)
            val calculatedTime = if (isSunrise) {
                calculator.getOfficialSunriseCalendarForDate(targetDate)
            } else {
                calculator.getOfficialSunsetCalendarForDate(targetDate)
            }
            calculatedTime?.let {
                it.set(Calendar.SECOND, 0)
                it.set(Calendar.MILLISECOND, 0)
                prefs.edit {
                    putLong(storedTimeKey, it.timeInMillis)
                    putString(storedDateKey, todayDateString)
                    apply()
                }
                return it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating sunrise/sunset for widget $appWidgetId: ${e.message}", e)
        }
        return null
    }
    
    fun scheduleNextUpdate(pseudToday: Calendar, context: Context, appWidgetId: Int) {
        val (latitude, longitude) = getCoordinates(context, appWidgetId)
        val todaySunrise = getSunriseSunsetTime(context, appWidgetId, latitude, longitude, pseudToday, true, false)
        val todaySunset = getSunriseSunsetTime(context, appWidgetId, latitude, longitude, pseudToday, false, false)

        if (todaySunrise == null || todaySunset == null) {
            Log.w(TAG, "Cannot schedule next update for $appWidgetId: Sunrise/sunset time not available.")
            cancelAlarm(context, appWidgetId)
            return
        }

        if (todaySunset.timeInMillis < todaySunrise.timeInMillis) {
            todaySunset.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val nowMillis = System.currentTimeMillis()
        val nextUpdateMillis = calculateNextUpdateTime(nowMillis, todaySunrise.timeInMillis, todaySunset.timeInMillis)
        
        scheduleWork(context, nextUpdateMillis, appWidgetId)
    }

    private fun scheduleWork(context: Context, nextUpdateMillis: Long, appWidgetId: Int) {
        val delay = nextUpdateMillis - System.currentTimeMillis()
        if (delay <= 0) {
            Log.d(TAG, "Calculated next update time is in the past for widget $appWidgetId. Scheduling for 5 minutes later.")
            // Avoid immediate re-trigger loop
            scheduleWork(context, System.currentTimeMillis() + 5 * 60 * 1000, appWidgetId)
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<SunriseUpdateWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AppWidgetManager.EXTRA_APPWIDGET_ID to appWidgetId))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag("WidgetUpdate$appWidgetId")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("WidgetUpdateWork$appWidgetId", ExistingWorkPolicy.REPLACE, workRequest)
        Log.i(TAG, "Scheduled WorkManager job for widget $appWidgetId at ${Date(nextUpdateMillis)}")
    }

    // MODIFIED: Simplified for 12-hour divisions
    private fun calculateNextUpdateTime(nowMillis: Long, sunriseMillis: Long, sunsetMillis: Long): Long {
        val nextSunriseMillis = sunriseMillis + 24 * 3600 * 1000L

        return if (nowMillis in sunriseMillis until sunsetMillis) {
            // --- Daytime ---
            val dayDuration = sunsetMillis - sunriseMillis
            val timeUnit = dayDuration / 12.0
            val timePassed = nowMillis - sunriseMillis
            val currentUnitIndex = (timePassed / timeUnit).toInt()
            (sunriseMillis + ((currentUnitIndex + 1) * timeUnit).roundToLong()).coerceAtMost(sunsetMillis)
        } else {
            // --- Nighttime ---
            val nightDuration = nextSunriseMillis - sunsetMillis
            val timeUnit = nightDuration / 12.0
            val effectiveStart = if (nowMillis < sunriseMillis) sunriseMillis - nightDuration else sunsetMillis
            val timePassed = nowMillis - effectiveStart
            val currentUnitIndex = (timePassed / timeUnit).toInt()
            (effectiveStart + ((currentUnitIndex + 1) * timeUnit).roundToLong()).coerceAtMost(nextSunriseMillis)
        }
    }
    
    fun cancelAlarm(context: Context, appWidgetId: Int) {
        WorkManager.getInstance(context).cancelUniqueWork("WidgetUpdateWork$appWidgetId")
        Log.d(TAG, "Cancelled existing WorkManager job for widget $appWidgetId")
    }

    class SunriseUpdateWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
        override fun doWork(): Result {
            val appWidgetId = inputData.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                return Result.failure()
            }
            val intent = Intent(applicationContext, Oriens::class.java).apply {
                action = ACTION_ALARM_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            applicationContext.sendBroadcast(intent)
            Log.i(TAG, "WorkManager executed for widget $appWidgetId, sent broadcast.")
            return Result.success()
        }
    }
}