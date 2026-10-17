package com.example.osuautoplayer.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object PreferencesManager {

    private var instance: PreferencesManager? = null
    private lateinit var prefs: SharedPreferences
    
    fun initialize(context: Context) {
        if (this::instance.isInitialized) return
        instance = PreferencesManager()
        instance?.prefs = PreferenceManager.getDefaultSharedPreferences(context)
    }
    
    fun getInstance(): PreferencesManager {
        if (!this::instance.isInitialized) {
            throw IllegalStateException("PreferencesManager not initialized")
        }
        return instance!!
    }

    // Capture settings
    val captureResolution: String
        get() = prefs.getString("capture_resolution", "1280x720")!!
    
    val processingFrequency: Int
        get() = prefs.getInt("processing_frequency", 30)
    
    // Detection settings
    val detectionThreshold: Int
        get() = prefs.getInt("detection_threshold", 30)
    
    val timingOffset: Int
        get() = prefs.getInt("timing_offset", 0)
    
    // Calibration settings
    val autoCalibrationEnabled: Boolean
        get() = prefs.getBoolean("auto_calibration_enabled", true)
    
    // Input settings
    val chordGroupingEnabled: Boolean
        get() = prefs.getBoolean("chord_grouping_enabled", true)
    
    val maxChordSize: Int
        get() = prefs.getInt("max_chord_size", 4)
    
    // Display settings
    val debugOverlayEnabled: Boolean
        get() = prefs.getBoolean("debug_overlay", false)
    
    val performanceLoggingEnabled: Boolean
        get() = prefs.getBoolean("performance_logging", false)
    
    // Advanced tracking settings
    val velocitySmoothingFactor: Float
        get() = prefs.getFloat("velocity_smoothing", 0.7f)
    
    val trackExpirationTime: Int
        get() = prefs.getInt("track_expiration_ms", 2000)
    
    // Hold note settings
    val holdTailDetectionEnabled: Boolean
        get() = prefs.getBoolean("hold_tail_detection", true)
    
    val minHoldDuration: Int
        get() = prefs.getInt("min_hold_duration_ms", 200)
}

// Extension to get float preferences safely
private fun SharedPreferences.getFloat(key: String, defaultValue: Float): Float {
    return getString(key)?.toFloatOrNull() ?: defaultValue
}
