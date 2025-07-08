package lab.rreedd.oriens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OriensClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "OriensClockView"

    private var sunriseTime: Calendar? = null
    private var sunsetTime: Calendar? = null

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            invalidate() // Redraw
            // Schedule next update (e.g., every minute)
            updateHandler.postDelayed(this, 60 * 1000L)
        }
    }

    // Paint objects
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val handPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    init {
        startUpdating()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUpdating()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopUpdating()
    }

    fun setSunriseSunsetTimes(sunrise: Calendar?, sunset: Calendar?) {
        var correctedSunset = sunset
        if (sunrise != null && correctedSunset != null && correctedSunset.timeInMillis < sunrise.timeInMillis) {
            correctedSunset = (correctedSunset.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        }
        this.sunriseTime = sunrise
        this.sunsetTime = correctedSunset
        invalidate() // Redraw with new times
    }

    private fun startUpdating() {
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.post(updateRunnable)
    }

    private fun stopUpdating() {
        updateHandler.removeCallbacks(updateRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f * 0.8f

        val currentTime = Calendar.getInstance()
        val (romanTimeDisplay, _) = calculateRomanTimeForDisplay(currentTime)

        // Draw clock circle
        canvas.drawCircle(centerX, centerY, radius, linePaint)

        // Display current Roman time in the center
        textPaint.textSize = 46f
        canvas.drawText(romanTimeDisplay, centerX, centerY - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        textPaint.textSize = 36f // Reset text size

        // Draw clock marks and hour hand if times are set
        sunriseTime?.let { sunrise ->
            sunsetTime?.let { sunset ->
                drawRomanClockMarks(canvas, centerX, centerY, radius)
                drawSystemClockMarks(canvas, centerX, centerY, radius * 1.25f, sunrise, sunset)
                drawRomanHourHand(canvas, centerX, centerY, radius * 0.65f, currentTime, sunrise, sunset)
            }
        }
    }

    private fun drawRomanClockMarks(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val labelTextSize = 36f * 0.9f
        textPaint.textSize = labelTextSize

        // 円周を24等分（15度ずつ）
        val romanNumerals = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

        // 昼の時間を描画（左から上の頂点を経由して右へ）
        for (i in 0 until 12) {
            val label = romanNumerals[i]
            // 左を-90度として、15度ずつ進む
            val angleDegrees = -90f + (i * 15f)
            val angleRadians = Math.toRadians(angleDegrees.toDouble()).toFloat()

            val labelRadius = radius * 0.90f
            val x = centerX + labelRadius * sin(angleRadians)
            val y = centerY - labelRadius * cos(angleRadians)

            canvas.drawText(label, x, y + textPaint.textSize / 2.5f, textPaint)
        }

        // 夜の時間を描画（右から下の頂点を経由して左へ）
        for (i in 0 until 12) {
            val label = romanNumerals[i]
            // 右を90度として、15度ずつ進む
            val angleDegrees = 90f + (i * 15f)
            val angleRadians = Math.toRadians(angleDegrees.toDouble()).toFloat()

            val labelRadius = radius * 0.90f
            val x = centerX + labelRadius * sin(angleRadians)
            val y = centerY - labelRadius * cos(angleRadians)

            canvas.drawText(label, x, y + textPaint.textSize / 2.5f, textPaint)
        }

        textPaint.textSize = 36f // Reset
    }

    private fun getTimeAngleForRomanClock(targetTimeMillis: Long, sunrise: Calendar, sunset: Calendar): Float {
        val todaySunrise = sunrise.timeInMillis
        val todaySunset = sunset.timeInMillis
        val nextDaySunrise = (sunrise.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

        // Normalize time to be within the current sunrise-to-next-sunrise cycle
        var effectiveTargetTimeMillis = targetTimeMillis
        while (effectiveTargetTimeMillis < todaySunrise) {
            effectiveTargetTimeMillis += 24 * 3600 * 1000L
        }
        while (effectiveTargetTimeMillis >= nextDaySunrise) {
            effectiveTargetTimeMillis -= 24 * 3600 * 1000L
        }

        val isDayTime = effectiveTargetTimeMillis in todaySunrise until todaySunset
        val progress: Float // Progress through the 12 hours (0.0 to 12.0)

        if (isDayTime) {
            val dayDuration = todaySunset - todaySunrise
            val timePassed = effectiveTargetTimeMillis - todaySunrise
            progress = (timePassed.toFloat() / dayDuration.toFloat()) * 12f
        } else {
            val nightDuration = nextDaySunrise - todaySunset
            val timePassed = effectiveTargetTimeMillis - todaySunset
            progress = (timePassed.toFloat() / nightDuration.toFloat()) * 12f
        }

        // 角度計算
        val finalAngleDegrees: Float
        if (isDayTime) {
            // 昼：左（-90度）から右（90度）まで180度
            finalAngleDegrees = -90f + (progress * 15f)
        } else {
            // 夜：右（90度）から左（270度）まで180度
            finalAngleDegrees = 90f + (progress * 15f)
        }

        return Math.toRadians(finalAngleDegrees.toDouble()).toFloat()
    }

    private fun calculateRomanTimeForDisplay(currentTime: Calendar): Pair<String, Map<String, Calendar?>> {
        val currentSunrise = sunriseTime
        val currentSunset = sunsetTime
        if (currentSunrise == null || currentSunset == null) {
            return Pair("Set Loc", mapOf("sunrise" to null, "sunset" to null))
        }

        val currentTimeMillis = currentTime.timeInMillis
        val sunriseMillis = currentSunrise.timeInMillis
        val sunsetMillis = currentSunset.timeInMillis
        val nextSunriseMillis = (currentSunrise.clone() as Calendar).apply{ add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis

        val horaDayLabelsShort = arrayOf("Prīma", "Secunda", "Tertia", "Quārta", "Quīnta", "Sexta", "Septima", "Octāva", "Nōna", "Decima", "Ūndecima", "Duodecima")

        val isDayTime = currentTimeMillis in sunriseMillis until sunsetMillis
        val timeLabel: String

        if (isDayTime) {
            val daytimeDuration = sunsetMillis - sunriseMillis
            val timePassed = currentTimeMillis - sunriseMillis
            val timeUnit = daytimeDuration / 12.0
            val unitIndex = (timePassed / timeUnit).toInt().coerceIn(0, 11)
            timeLabel = "Diēī ${horaDayLabelsShort[unitIndex]}"
        } else {
            val nighttimeDuration = nextSunriseMillis - sunsetMillis
            val effectiveStart = if (currentTimeMillis < sunriseMillis) {
                (currentSunset.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            } else {
                sunsetMillis
            }
            val timePassed = currentTimeMillis - effectiveStart
            val timeUnit = nighttimeDuration / 12.0
            val unitIndex = (timePassed / timeUnit).toInt().coerceIn(0, 11)
            timeLabel = "Noctis ${horaDayLabelsShort[unitIndex]}"
        }

        return Pair(timeLabel, mapOf("sunrise" to currentSunrise, "sunset" to currentSunset))
    }

    private fun drawSystemClockMarks(canvas: Canvas, centerX: Float, centerY: Float, outerRadius: Float, sunrise: Calendar, sunset: Calendar) {
        textPaint.textSize = 36f * 0.7f
        textPaint.color = ContextCompat.getColor(context, R.color.system_clock_text_color)
        val textRadius = outerRadius * 0.9f

        // 日の出時刻を取得（時のみ）
        val sunriseHour = sunrise.get(Calendar.HOUR_OF_DAY)
        val sunsetHour = sunset.get(Calendar.HOUR_OF_DAY)

        // 24時間のシステム時刻を配置
        for (i in 0 until 24) {
            val hour = i
            val displayHour = if (hour == 0) "24" else hour.toString()

            // 日の出時刻を左（-90度）、日の入り時刻を右（90度）に配置
            // 24時間を360度で配置（15度ずつ）
            val hourOffset = (hour - sunriseHour + 24) % 24
            val angleDegrees = -90f + (hourOffset * 15f)

            val angleRadians = Math.toRadians(angleDegrees.toDouble()).toFloat()
            val textX = centerX + textRadius * sin(angleRadians)
            val textY = centerY - textRadius * cos(angleRadians) + textPaint.textSize / 2.5f
            canvas.drawText(displayHour, textX, textY, textPaint)
        }

        textPaint.textSize = 36f
        textPaint.color = Color.WHITE
    }

    private fun drawRomanHourHand(canvas: Canvas, centerX: Float, centerY: Float, length: Float, currentTime: Calendar, sunrise: Calendar, sunset: Calendar) {
        val angleRadians = getTimeAngleForRomanClock(currentTime.timeInMillis, sunrise, sunset)
        val endX = centerX + length * sin(angleRadians)
        val endY = centerY - length * cos(angleRadians)
        canvas.drawLine(centerX, centerY, endX, endY, handPaint)
    }
}