package com.example.osuautoplayer.input

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.example.osuautoplayer.tracking.NoteTracker
import com.example.osuautoplayer.processing.FrameProcessor

/**
 * TouchInputService - AccessibilityService for injecting touch inputs
 * to play osu! using dispatchGesture() and GestureDescription.
 *
 * KEY DESIGN:
 * - Uses GestureDescription with dispatchGesture() (not ACTION_CLICK)
 * - Centralized gesture coordinator (InputScheduler) owns all active gestures
 * - Acknowledges that dispatching a new gesture may cancel existing ones
 * - Does not claim persistent pointer ownership beyond what the API guarantees
 * - Provides emergency stop and safe release of all active gestures
 */
class TouchInputService : AccessibilityService() {

    private lateinit var inputScheduler: InputScheduler
    private lateinit var noteTracker: NoteTracker
    private val mainHandler = Handler(Looper.getMainLooper())

    // Service state
    private var isInputActive = false
    private var lastErrorTime: Long = 0
    private val errorCooldownMs = 5000

    override fun onCreate() {
        super.onCreate()
        noteTracker = NoteTracker()
        inputScheduler = InputScheduler(this, noteTracker)
        isInputActive = false
    }

    override fun onDestroy() {
        // Safe shutdown - release all gestures first
        try {
            inputScheduler.releaseAllGestures()
        } catch (e: Exception) {
            // Ignore errors during shutdown
        }

        inputScheduler.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        // We don't allow binding to this service
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle accessibility events for this service
        // The service is used purely for its ability to dispatch gestures
    }

    override fun onInterrupt() {
        // Service has been interrupted - release all active gestures safely
        releaseAllGesturesSafely()
    }

    /**
     * Get the input scheduler for external access
     */
    fun getInputScheduler(): InputScheduler {
        return inputScheduler
    }

    /**
     * Get the note tracker for external access
     */
    fun getNoteTracker(): NoteTracker {
        return noteTracker
    }

    /**
     * Check if any gesture/input is currently active
     */
    fun isInputActive(): Boolean {
        return isInputActive || inputScheduler.getActiveGestureCount() > 0
    }

    /**
     * Release all gestures safely with error handling
     */
    fun releaseAllGesturesSafely() {
        try {
            inputScheduler.releaseAllGestures()
            isInputActive = false
        } catch (e: Exception) {
            logError("Failed to release all gestures", e)
        }
    }

    /**
     * Emergency stop - immediately stop all processing and release all gestures
     */
    fun emergencyStop() {
        releaseAllGesturesSafely()
    }

    private fun logError(message: String, exception: Exception?) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastErrorTime < errorCooldownMs) return

        lastErrorTime = currentTime

        if (exception != null) {
            android.util.Log.e("TouchInputService", message, exception)
        } else {
            android.util.Log.e("TouchInputService", message)
        }
    }
}
