package com.example.osuautoplayer.utils

import android.util.Log
import com.example.osuautoplayer.input.InputScheduler
import com.example.osuautoplayer.tracking.NoteTracker
import com.example.osuautoplayer.settings.ProfileManager
import com.example.osuautoplayer.calibration.JudgementLineCalibrator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ErrorRecoveryManager handles error recovery, edge cases, and graceful degradation
 * for the osu! autoplayer application.
 */
class ErrorRecoveryManager {

    private companion object {
        private const val TAG = "ErrorRecoveryManager"
    }

    // Recovery state
    private val isRecovering = AtomicBoolean(false)
    private val errorHistory = ConcurrentHashMap<String, ErrorRecord>()
    private val maxErrorHistory = 100
    
    // Recovery configuration
    private var maxConsecutiveErrors = 5
    private var recoveryDelayMs = 1000
    private var maxRecoveryAttempts = 3
    private var enableAutoRecovery = true
    
    // Error counters
    private var consecutiveErrors = AtomicLong(0)
    private var totalErrors = AtomicLong(0)
    private var totalRecoveries = AtomicLong(0)
    private var lastErrorTime: Long = 0
    
    // Component references for recovery
    private var inputScheduler: InputScheduler? = null
    private var noteTracker: NoteTracker? = null
    private var profileManager: ProfileManager? = null
    private var judgementLineCalibrator: JudgementLineCalibrator? = null
    
    // Recovery callbacks
    private val recoveryCallbacks = ConcurrentHashMap<String, RecoveryCallback>()

    /**
     * Initialize with component references for recovery operations
     */
    fun initialize(
        inputScheduler: InputScheduler? = null,
        noteTracker: NoteTracker? = null,
        profileManager: ProfileManager? = null,
        judgementLineCalibrator: JudgementLineCalibrator? = null
    ) {
        this.inputScheduler = inputScheduler
        this.noteTracker = noteTracker
        this.profileManager = profileManager
        this.judgementLineCalibrator = judgementLineCalibrator
    }

    /**
     * Handle an error with automatic recovery
     */
    fun handleError(
        errorType: ErrorType,
        message: String,
        exception: Exception? = null,
        context: String = "",
        recoverable: Boolean = true
    ): RecoveryResult {
        val errorId = "${errorType.name}_${System.nanoTime()}"
        val errorRecord = ErrorRecord(
            id = errorId,
            type = errorType,
            message = message,
            exception = exception,
            context = context,
            timestamp = System.currentTimeMillis(),
            recoverable = recoverable
        )
        
        // Log error
        if (exception != null) {
            Log.e(TAG, "$message (context: $context)", exception)
        } else {
            Log.e(TAG, "$message (context: $context)")
        }
        
        // Store error record
        errorHistory[errorId] = errorRecord
        if (errorHistory.size > maxErrorHistory) {
            // Remove oldest error
            val oldestKey = errorHistory.keys.minBy { errorHistory[it]?.timestamp ?: 0L }
            oldestKey?.let { errorHistory.remove(it) }
        }
        
        // Update counters
        totalErrors.incrementAndGet()
        consecutiveErrors.incrementAndGet()
        lastErrorTime = System.currentTimeMillis()
        
        // Attempt recovery if enabled and recoverable
        var recovered = false
        var recoveryAction: RecoveryAction? = null
        
        if (recoverable && enableAutoRecovery && consecutiveErrors.get() <= maxConsecutiveErrors) {
            val result = attemptRecovery(errorType, errorRecord)
            recovered = result.recovered
            recoveryAction = result.action
            
            if (recovered) {
                totalRecoveries.incrementAndGet()
                consecutiveErrors.set(0)
                Log.i(TAG, "Successfully recovered from error: $errorId")
            }
        } else if (!recoverable) {
            Log.w(TAG, "Non-recoverable error: $errorId")
            consecutiveErrors.set(0) // Reset for non-recoverable errors
        }
        
        // Check if we need to enter recovery mode
        if (consecutiveErrors.get() >= maxConsecutiveErrors) {
            Log.w(TAG, "Too many consecutive errors, entering recovery mode")
            isRecovering.set(true)
        }
        
        return RecoveryResult(
            errorId = errorId,
            recovered = recovered,
            action = recoveryAction,
            message = message
        )
    }

    /**
     * Attempt automatic recovery based on error type
     */
    private fun attemptRecovery(errorType: ErrorType, errorRecord: ErrorRecord): RecoveryResult {
        isRecovering.set(true)
        
        val action = when (errorType) {
            ErrorType.INPUT_DISPATCH_FAILED -> recoverInputDispatch()
            ErrorType.TRACKING_LOST -> recoverTrackingLost()
            ErrorType.CAPTURE_PERMISSION_REVOKED -> recoverCapturePermission()
            ErrorType.ACCESSIBILITY_SERVICE_DISCONNECTED -> recoverAccessibilityService()
            ErrorType.OVERLAY_PERMISSION_REVOKED -> recoverOverlayPermission()
            ErrorType.PROFILE_CORRUPTED -> recoverProfileCorrupted()
            ErrorType.CALIBRATION_FAILED -> recoverCalibrationFailed()
            ErrorType.MEMORY_LOW -> recoverMemoryLow()
            ErrorType.SCREEN_ROTATION -> recoverScreenRotation()
            ErrorType.GAME_CLOSED -> recoverGameClosed()
            ErrorType.UNKNOWN -> recoverUnknown()
        }
        
        isRecovering.set(false)
        
        return RecoveryResult(
            errorId = errorRecord.id,
            recovered = action != RecoveryAction.NONE,
            action = action,
            message = errorRecord.message
        )
    }

    // ============ Individual Recovery Strategies ============

    private fun recoverInputDispatch(): RecoveryAction {
        Log.i(TAG, "Attempting input dispatch recovery")
        
        // Release all active inputs to clear stuck states
        inputScheduler?.releaseAllInputs()
        
        return RecoveryAction.RELEASE_ALL_INPUTS
    }

    private fun recoverTrackingLost(): RecoveryAction {
        Log.i(TAG, "Attempting tracking recovery")
        
        // NoteTracker handles recovery internally through prediction
        // We just need to ensure it's in a clean state
        val activeTrackCount = noteTracker?.let { it.activeTrackCount } ?: 0
        Log.i(TAG, "Active tracks after recovery attempt: $activeTrackCount")
        
        return RecoveryAction.RESET_TRACKING
    }

    private fun recoverCapturePermission(): RecoveryAction {
        Log.w(TAG, "Capture permission revoked - cannot auto-recover")
        return RecoveryAction.REQUIRE_USER_ACTION
    }

    private fun recoverAccessibilityService(): RecoveryAction {
        Log.w(TAG, "Accessibility service disconnected - cannot auto-recover")
        return RecoveryAction.REQUIRE_USER_ACTION
    }

    private fun recoverOverlayPermission(): RecoveryAction {
        Log.w(TAG, "Overlay permission revoked - cannot auto-recover")
        return RecoveryAction.REQUIRE_USER_ACTION
    }

    private fun recoverProfileCorrupted(): RecoveryAction {
        Log.i(TAG, "Attempting profile recovery")
        
        // Try to restore known-good profile
        profileManager?.let { pm ->
            val knownGood = pm.restoreKnownGoodProfile()
            if (knownGood != null) {
                Log.i(TAG, "Restored known-good profile: ${knownGood.name}")
                return RecoveryAction.RESTORE_PROFILE
            }
        }
        
        return RecoveryAction.RESET_TO_DEFAULT
    }

    private fun recoverCalibrationFailed(): RecoveryAction {
        Log.i(TAG, "Calibration failed - preserving existing calibration")
        
        // Don't overwrite existing calibration
        // Let user manually adjust
        return RecoveryAction.PRESERVE_EXISTING
    }

    private fun recoverMemoryLow(): RecoveryAction {
        Log.i(TAG, "Memory low - clearing caches")
        
        // Clear error history to free memory
        errorHistory.clear()
        
        return RecoveryAction.CLEAR_CACHES
    }

    private fun recoverScreenRotation(): RecoveryAction {
        Log.i(TAG, "Handling screen rotation")
        
        // Input scheduler should handle this gracefully
        // Tracking will need to re-establish after rotation
        return RecoveryAction.REESTABLISH_TRACKING
    }

    private fun recoverGameClosed(): RecoveryAction {
        Log.i(TAG, "Game closed - releasing all inputs")
        
        inputScheduler?.releaseAllInputs()
        
        return RecoveryAction.RELEASE_ALL_INPUTS
    }

    private fun recoverUnknown(): RecoveryAction {
        Log.w(TAG, "Unknown error - attempting safe reset")
        
        inputScheduler?.releaseAllInputs()
        
        return RecoveryAction.SAFE_RESET
    }

    // ============ Public API ============

    fun isInRecoveryMode(): Boolean {
        return isRecovering.get()
    }

    fun getErrorHistory(): List<ErrorRecord> {
        return errorHistory.values.toList()
    }

    fun getRecentErrors(limit: Int = 10): List<ErrorRecord> {
        return errorHistory.values
            .sortedByDescending { it.timestamp }
            .take(limit)
            .toList()
    }

    fun getErrorCount(): Long {
        return totalErrors.get()
    }

    fun getRecoveryCount(): Long {
        return totalRecoveries.get()
    }

    fun getConsecutiveErrorCount(): Long {
        return consecutiveErrors.get()
    }

    fun clearErrorHistory() {
        errorHistory.clear()
        consecutiveErrors.set(0)
        isRecovering.set(false)
    }

    fun registerRecoveryCallback(component: String, callback: RecoveryCallback) {
        recoveryCallbacks[component] = callback
    }

    fun unregisterRecoveryCallback(component: String) {
        recoveryCallbacks.remove(component)
    }

    // ============ Configuration ============

    fun setMaxConsecutiveErrors(count: Int) {
        maxConsecutiveErrors = count
    }

    fun setRecoveryDelayMs(delay: Long) {
        recoveryDelayMs = delay
    }

    fun setMaxRecoveryAttempts(attempts: Int) {
        maxRecoveryAttempts = attempts
    }

    fun setAutoRecoveryEnabled(enabled: Boolean) {
        enableAutoRecovery = enabled
    }

    // ============ Edge Case Handlers ============

    /**
     * Handle hold note during screen rotation
     */
    fun handleHoldDuringRotation(holdId: String): RecoveryAction {
        Log.i(TAG, "Handling hold $holdId during screen rotation")
        
        // Preserve hold state through rotation
        val holdState = noteTracker?.getHoldState(holdId)
        if (holdState?.state?.let { 
            it == NoteTracker.HoldTrackState.HoldState.HOLD_ACTIVE 
        } == true) {
            // Mark hold as needing re-establishment after rotation
            return RecoveryAction.PRESERVE_HOLD
        }
        
        return RecoveryAction.NONE
    }

    /**
     * Handle new notes arriving during active hold
     */
    fun handleNotesDuringHold(
        activeHoldId: String,
        newNotes: List<String>
    ): RecoveryAction {
        Log.i(TAG, "Handling ${newNotes.size} new notes during hold $activeHoldId")
        
        // Preserve active hold
        // Schedule new notes normally
        // InputScheduler handles this through active input registry
        
        return RecoveryAction.PRESERVE_HOLD
    }

    /**
     * Handle simultaneous hold starts
     */
    fun handleSimultaneousHolds(holdIds: List<String>): RecoveryAction {
        Log.i(TAG, "Handling ${holdIds.size} simultaneous holds")
        
        // InputScheduler handles multi-pointer gestures
        // Tracker maintains separate hold states
        
        return RecoveryAction.MULTI_POINTER_GESTURE
    }

    /**
     * Handle hold ending while new notes arrive
     */
    fun handleHoldEndingWithNewNotes(
        endingHoldId: String,
        newNoteIds: List<String>
    ): RecoveryAction {
        Log.i(TAG, "Hold $endingHoldId ending with ${newNoteIds.size} new notes")
        
        // Release ending hold
        // Schedule new notes
        // InputScheduler ensures correct pointer release
        
        return RecoveryAction.SEQUENTIAL_RELEASE
    }

    /**
     * Handle cancelled or failed gestures
     */
    fun handleGestureFailure(
        gestureId: String,
        reason: String
    ): RecoveryAction {
        Log.w(TAG, "Gesture $gestureId failed: $reason")
        
        // Release the failed gesture
        inputScheduler?.releaseAllInputs()
        
        // Mark related holds as failed
        noteTracker?.getHoldTrackingData().forEach { (id, state) ->
            if (state.state == NoteTracker.HoldTrackState.HoldState.HOLD_ACTIVE) {
                noteTracker?.updateHoldStateManual(
                    id,
                    NoteTracker.HoldTrackState.HoldState.FAILED,
                    System.currentTimeMillis()
                )
            }
        }
        
        return RecoveryAction.RELEASE_ALL_INPUTS
    }

    /**
     * Safe shutdown with input release
     */
    fun safeShutdown() {
        Log.i(TAG, "Performing safe shutdown")
        
        // Release all active inputs
        inputScheduler?.releaseAllInputs()
        
        // Clear tracking state
        noteTracker?.let { tracker ->
            tracker.getHoldTrackingData().keys.forEach { holdId ->
                tracker.updateHoldStateManual(
                    holdId,
                    NoteTracker.HoldTrackState.HoldState.LOST,
                    System.currentTimeMillis()
                )
            }
        }
        
        // Clear error history
        errorHistory.clear()
        isRecovering.set(false)
    }
}

// ============ Data Classes ============

data class ErrorRecord(
    val id: String,
    val type: ErrorType,
    val message: String,
    val exception: Exception?,
    val context: String,
    val timestamp: Long,
    val recoverable: Boolean
)

data class RecoveryResult(
    val errorId: String,
    val recovered: Boolean,
    val action: RecoveryAction,
    val message: String
)

enum class ErrorType {
    INPUT_DISPATCH_FAILED,
    TRACKING_LOST,
    CAPTURE_PERMISSION_REVOKED,
    ACCESSIBILITY_SERVICE_DISCONNECTED,
    OVERLAY_PERMISSION_REVOKED,
    PROFILE_CORRUPTED,
    CALIBRATION_FAILED,
    MEMORY_LOW,
    SCREEN_ROTATION,
    GAME_CLOSED,
    UNKNOWN
}

enum class RecoveryAction {
    NONE,
    RELEASE_ALL_INPUTS,
    RESET_TRACKING,
    REQUIRE_USER_ACTION,
    RESTORE_PROFILE,
    RESET_TO_DEFAULT,
    PRESERVE_EXISTING,
    CLEAR_CACHES,
    REESTABLISH_TRACKING,
    PRESERVE_HOLD,
    MULTI_POINTER_GESTURE,
    SEQUENTIAL_RELEASE,
    SAFE_RESET
}

/**
 * Callback interface for component-specific recovery
 */
interface RecoveryCallback {
    fun onRecoveryStarted(errorType: ErrorType)
    fun onRecoveryCompleted(action: RecoveryAction, success: Boolean)
    fun onRecoveryFailed(errorType: ErrorType, reason: String)
}
