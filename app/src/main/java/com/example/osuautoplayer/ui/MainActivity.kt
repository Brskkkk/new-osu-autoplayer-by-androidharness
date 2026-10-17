package com.example.osuautoplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.osuautoplayer.R
import com.example.osuautoplayer.capture.ScreenCaptureService
import com.example.osuautoplayer.input.TouchInputService
import com.example.osuautoplayer.processing.FrameProcessor
import com.example.osuautoplayer.settings.PreferencesManager

class MainActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: Button
    private lateinit var pauseButton: Button
    private lateinit var autoAdjustButton: Button
    private lateinit var manualCalibrationButton: Button
    private lateinit var releaseAllButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var debugOverlayView: DebugOverlayView
    
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startServices()
            updateStatus("Services started")
        } else {
            updateStatus("Media projection permission denied")
        }
    }

    private val accessibilityServiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (isAccessibilityServiceEnabled()) {
            updateStatus("Accessibility service enabled")
        } else {
            updateStatus("Please enable accessibility service")
        }
    }

    // References to services for UI updates
    private var screenCaptureService: ScreenCaptureService? = null
    private var noteProcessingService: NoteProcessingService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize UI components
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        settingsButton = findViewById(R.id.settingsButton)
        pauseButton = findViewById(R.id.pauseButton)
        autoAdjustButton = findViewById(R.id.autoAdjustButton)
        manualCalibrationButton = findViewById(R.id.manualCalibrationButton)
        releaseAllButton = findViewById(R.id.releaseAllButton)
        statusTextView = findViewById(R.id.statusTextView)
        debugOverlayView = findViewById(R.id.debugOverlay)
        
        // Set up button click listeners
        startButton.setOnClickListener { requestPermissionsAndStart() }
        stopButton.setOnClickListener { stopServices() }
        pauseButton.setOnClickListener { togglePause() }
        settingsButton.setOnClickListener { openSettings() }
        autoAdjustButton.setOnClickListener { autoAdjustJudgementLine() }
        manualCalibrationButton.setOnClickListener { openManualCalibration() }
        releaseAllButton.setOnClickListener { releaseAllInputs() }
        
        updateInitialStatus()
        
        // Initialize PreferencesManager
        PreferencesManager.initialize(this)
    }

    private fun updateInitialStatus() {
        val sb = StringBuilder()
        sb.append("Status: ").append(if (isOverlayPermissionGranted()) "Overlay OK, " else "Overlay DENIED, ")
        sb.append(if (isMediaProjectionPermissionGranted()) "Capture OK, " else "Capture DENIED, ")
        sb.append(if (isAccessibilityServiceEnabled()) "Accessibility OK" else "Accessibility DENIED")
        updateStatus(sb.toString())
    }

    private fun requestPermissionsAndStart() {
        val missingPermissions = mutableListOf<String>()
        
        if (!isOverlayPermissionGranted()) {
            missingPermissions.add("OVERLAY")
        }
        if (!isMediaProjectionPermissionGranted()) {
            missingPermissions.add("MEDIA_PROJECTION")
        }
        if (!isAccessibilityServiceEnabled()) {
            missingPermissions.add("ACCESSIBILITY")
        }
        
        if (missingPermissions.isEmpty()) {
            startServices()
        } else {
            requestMissingPermissions(missingPermissions)
        }
    }

    private fun requestMissingPermissions(missingPermissions: List<String>) {
        val messages = mutableListOf<String>()
        if (missingPermissions.contains("OVERLAY")) {
            messages.append("Draw over other apps permission")
        }
        if (missingPermissions.contains("MEDIA_PROJECTION")) {
            messages.append("Screen capture permission")
        }
        if (missingPermissions.contains("ACCESSIBILITY")) {
            messages.append("Accessibility service permission")
        }
        
        statusTextView.text = "Please grant: ${messages.joinToString(", ")}"
        
        if (missingPermissions.contains("OVERLAY")) {
            openOverlaySettings()
        }
        if (missingPermissions.contains("MEDIA_PROJECTION")) {
            requestMediaProjectionPermission()
        }
        if (missingPermissions.contains("ACCESSIBILITY")) {
            requestAccessibilityServicePermission()
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun requestMediaProjectionPermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = manager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent.intentSender)
    }

    private fun requestAccessibilityServicePermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilityServiceLauncher.launch(intent)
    }

    private fun startServices() {
        // Start screen capture service
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
        // In a real implementation, we'd pass the media projection result here
        ContextCompat.startForegroundService(this, captureIntent)
        
        // Start note processing service
        val processingIntent = Intent(this, NoteProcessingService::class.java)
        ContextCompat.startForegroundService(this, processingIntent)
        
        updateStatus("Services started")
    }

    private fun stopServices() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, NoteProcessingService::class.java))
        stopService(Intent(this, TouchInputService::class.java))
        updateStatus("Services stopped")
    }

    private fun togglePause() {
        // TODO: Implement pause/resume functionality
        updateStatus("Pause functionality not yet implemented")
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun autoAdjustJudgementLine() {
        // TODO: Implement auto adjustment
        updateStatus("Auto adjust functionality not yet implemented")
    }

    private fun openManualCalibration() {
        // TODO: Implement manual calibration screen
        updateStatus("Manual calibration not yet implemented")
    }

    private fun releaseAllInputs() {
        // TODO: Implement release all inputs
        updateStatus("Release all inputs not yet implemented")
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isMediaProjectionPermissionGranted(): Boolean {
        // Simple check - in practice, we'd need to track the actual permission
        return true // Placeholder
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = 
            getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = 
            accessibilityManager.getEnabledAccessibilityServiceList(
                android.view.accessibility.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { 
            it.id == "${packageName}/com.example.osuautoplayer.input.TouchInputService" 
        }
    }

    private fun updateStatus(message: String) {
        statusTextView.text = message
    }
}
