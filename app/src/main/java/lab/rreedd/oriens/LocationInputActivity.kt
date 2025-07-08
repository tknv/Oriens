package lab.rreedd.oriens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import java.util.Calendar
import java.util.regex.Pattern

private const val TAG_INPUT_ACTIVITY = "LocationInputActivity"
private const val PREFS_NAME = "lab.rreedd.oriens.OriensWidgetPrefs"
private const val PREF_LATITUDE_PREFIX = "latitude_"
private const val PREF_LONGITUDE_PREFIX = "longitude_"
const val DEFAULT_LATITUDE_STRING = "41.89862723117168"
const val DEFAULT_LONGITUDE_STRING = "12.476861603108699"

class LocationInputActivity : AppCompatActivity() {

    private lateinit var editTextLatitude: EditText
    private lateinit var editTextLongitude: EditText
    private lateinit var buttonApply: Button
    private lateinit var oriensClockView: OriensClockView // Changed from JapaneseClockView

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    companion object {
        const val ACTION_LOCATION_UPDATED = "lab.rreedd.oriens.ACTION_LOCATION_UPDATED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_input)
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        editTextLatitude = findViewById(R.id.editTextLatitude)
        editTextLongitude = findViewById(R.id.editTextLongitude)
        buttonApply = findViewById(R.id.buttonApply)
        oriensClockView = findViewById(R.id.oriensClockView) // Changed ID and type

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        loadSavedCoordinates()
        setupValidation()
        updateClockView() // Initial clock update

        buttonApply.setOnClickListener {
            saveCoordinatesAndFinish()
        }
        
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Parsing logic for geo URIs and shared text can remain the same
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            intent.data?.let { uri ->
                 if ("geo".equals(uri.scheme, ignoreCase = true)) {
                    val path = uri.schemeSpecificPart.split("?")[0]
                    val parts = path.split(",")
                    if (parts.size >= 2) {
                        editTextLatitude.setText(parts[0])
                        editTextLongitude.setText(parts[1])
                        validateAndEnableApplyButton()
                        updateClockView()
                    }
                 }
            }
        }
    }


    private fun loadSavedCoordinates() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val targetId = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) appWidgetId else getFirstWidgetId()

        val latString = prefs.getString(PREF_LATITUDE_PREFIX + targetId, DEFAULT_LATITUDE_STRING)
        val lonString = prefs.getString(PREF_LONGITUDE_PREFIX + targetId, DEFAULT_LONGITUDE_STRING)

        editTextLatitude.setText(latString)
        editTextLongitude.setText(lonString)
    }
    
    private fun getFirstWidgetId(): Int {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, Oriens::class.java) // Changed to Oriens
        return appWidgetManager.getAppWidgetIds(componentName).firstOrNull() ?: 0
    }

    private fun setupValidation() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateAndEnableApplyButton()
                // Update clock view in real-time as user types valid coordinates
                if (buttonApply.isEnabled) {
                    updateClockView()
                }
            }
        }
        editTextLatitude.addTextChangedListener(textWatcher)
        editTextLongitude.addTextChangedListener(textWatcher)
        validateAndEnableApplyButton()
    }

    private fun validateAndEnableApplyButton() {
        val latStr = editTextLatitude.text.toString()
        val lonStr = editTextLongitude.text.toString()
        val lat = latStr.toDoubleOrNull()
        val lon = lonStr.toDoubleOrNull()
        
        val latValid = lat != null && lat in -90.0..90.0
        val lonValid = lon != null && lon in -180.0..180.0
        
        editTextLatitude.error = if (latStr.isNotEmpty() && !latValid) getString(R.string.invalid_latitude) else null
        editTextLongitude.error = if (lonStr.isNotEmpty() && !lonValid) getString(R.string.invalid_longitude) else null

        buttonApply.isEnabled = latValid && lonValid
    }

    private fun saveCoordinatesAndFinish() {
        val latitude = editTextLatitude.text.toString()
        val longitude = editTextLongitude.text.toString()
        
        if (!buttonApply.isEnabled) {
            Toast.makeText(this, getString(R.string.invalid_coordinates), Toast.LENGTH_SHORT).show()
            return
        }

        val idsToUpdate = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Started for a specific widget
            intArrayOf(appWidgetId)
        } else {
            // Started as a general app screen, update all widgets
            getFirstWidgetId().let {
                val appWidgetManager = AppWidgetManager.getInstance(this)
                val componentName = ComponentName(this, Oriens::class.java) // Changed to Oriens
                appWidgetManager.getAppWidgetIds(componentName)
            }
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            idsToUpdate.forEach { id ->
                putString(PREF_LATITUDE_PREFIX + id, latitude)
                putString(PREF_LONGITUDE_PREFIX + id, longitude)
            }
            apply()
        }

        Toast.makeText(this, getString(R.string.location_saved), Toast.LENGTH_SHORT).show()

        // Trigger widget update
        if (idsToUpdate.isNotEmpty()) {
            val updateIntent = Intent(this, Oriens::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, idsToUpdate)
            }
            sendBroadcast(updateIntent)
        }

        // If this was a configuration activity, set the result and finish.
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }

    private fun updateClockView() {
        val latitude = editTextLatitude.text.toString().toDoubleOrNull() ?: return
        val longitude = editTextLongitude.text.toString().toDoubleOrNull() ?: return
        val today = Calendar.getInstance()
        
        // Force recalculation for the preview clock
        val sunriseTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(this, 0, latitude, longitude, today, true, true)
        val sunsetTime = SunriseWidgetAlarmUtils.getSunriseSunsetTime(this, 0, latitude, longitude, today, false, true)

        oriensClockView.setSunriseSunsetTimes(sunriseTime, sunsetTime)
    }
}