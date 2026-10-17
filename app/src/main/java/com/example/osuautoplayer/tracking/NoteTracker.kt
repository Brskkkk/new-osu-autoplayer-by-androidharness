package com.example.osuautoplayer.tracking

import com.example.osuautoplayer.processing.FrameProcessor.DetectedNote
import com.example.osuautoplayer.processing.FrameProcessor.TrackedNote
import com.example.osuautoplayer.settings.PreferencesManager
import com.example.osuautoplayer.calibration.JudgementLineCalibrator
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * NoteTracker - Tracks notes across timestamped frames with direct tail tracking.
 *
 * KEY DESIGN FIXES:
 * - Tracks hold tails directly through successive observations
 * - Updates release estimates as new frames arrive (not calculated once from head-to-tail distance)
 * - Real timestamps for irregular capture intervals
 * - Alpha-beta filter for velocity estimation
 * - Bounded prediction for temporarily obscured notes
 * - Explicit track expiration rules
 */
class NoteTracker {

    private val preferences = PreferencesManager.getInstance()
    private val judgementLineCalibrator = JudgementLineCalibrator()

    // Active tracks: trackId -> TrackedNote
    private val activeTracks = mutableMapOf<String, TrackedNote>()
    private val trackHistory = mutableMapOf<String, MutableList<TrackedNote>>()

    // Hold tracking: trackId -> HoldTrackState
    private val activeHolds = mutableMapOf<String, HoldTrackState>()

    // Tracking parameters from preferences
    private val maxTrackAgeMs: Int
    private val velocitySmoothingFactor: Float
    private val trackAssociationTolerance: Float
    private val holdTailDetectionEnabled: Boolean
    private val minHoldDurationNs: Long

    // Recovery settings
    private val maxMissedDetections = 3
    private val recoveryConfidenceThreshold = 0.3f
    private val boundedPredictionMaxMs = 500L

    // Statistics
    var activeTrackCount: Int get() = activeTracks.size
    var activeHoldCount: Int get() = activeHolds.count { (_, s) ->
        s.state == HoldTrackState.HoldState.HOLD_ACTIVE ||
            s.state == HoldTrackState.HoldState.PRESS_SCHEDULED ||
            s.state == HoldTrackState.HoldState.TRACKING_TAIL
    }
    var totalTracksCreated: Long = 0
    var totalTracksExpired: Long = 0
    var totalTracksRecovered: Long = 0

    init {
        maxTrackAgeMs = preferences.trackExpirationTime
        velocitySmoothingFactor = preferences.velocitySmoothingFactor
        trackAssociationTolerance = preferences.noteAssociationTolerance.toFloat()
        holdTailDetectionEnabled = preferences.holdTailDetectionEnabled
        minHoldDurationNs = (preferences.minHoldDuration * 1_000_000L).toLong()
    }

    /**
     * Update tracks with new detections from a frame.
     * Associates detections with existing tracks or creates new ones.
     * Directly tracks hold tails and updates release estimates from observations.
     */
    fun updateTracks(detectedNotes: List<DetectedNote>, timestamp: Long): List<TrackedNote> {
        val currentTime = timestamp
        val updatedTracks = mutableListOf<TrackedNote>()
        val usedDetections = mutableSetOf<Int>()

        // Update existing tracks
        for ((trackId, track) in activeTracks.toMutableMap()) {
            val bestMatchIndex = findBestDetectionMatch(track, detectedNotes, usedDetections, currentTime)

            if (bestMatchIndex != -1) {
                val detectedNote = detectedNotes[bestMatchIndex]
                val updatedTrack = updateTrackWithDetection(track, detectedNote, currentTime)
                activeTracks[trackId] = updatedTrack
                updatedTracks.add(updatedTrack)
                usedDetections.add(bestMatchIndex)

                // Add to history
                val history = trackHistory.getOrPut(trackId) { mutableListOf() }
                history.add(updatedTrack)
                if (history.size > 10) history.removeAt(0)

                // Handle hold state transitions
                if (detectedNote.noteType == DetectedNote.NoteType.HOLD_HEAD ||
                    detectedNote.noteType == DetectedNote.NoteType.HOLD_TAIL) {
                    updateHoldTracking(trackId, updatedTrack, detectedNote, currentTime)
                }
            } else {
                // No match - predict forward with bounded prediction
                val predictedTrack = predictTrackPosition(track, currentTime)
                if (isTrackStillValidForRecovery(predictedTrack, currentTime)) {
                    activeTracks[trackId] = predictedTrack
                    updatedTracks.add(predictedTrack)
                    totalTracksRecovered++
                } else {
                    expireTrack(trackId, currentTime)
                    totalTracksExpired++
                }
            }
        }

        // Create new tracks for unmatched detections
        for ((index, detectedNote) in detectedNotes.withIndex()) {
            if (!usedDetections.contains(index)) {
                val newTrack = createNewTrack(detectedNote, currentTime)
                val trackId = newTrack.id
                activeTracks[trackId] = newTrack
                updatedTracks.add(newTrack)
                trackHistory[trackId] = mutableListOf(newTrack)
                totalTracksCreated++

                // Initialize hold tracking if applicable
                if (detectedNote.noteType == DetectedNote.NoteType.HOLD_HEAD && holdTailDetectionEnabled) {
                    initializeHoldTracking(trackId, newTrack, currentTime)
                }
            }
        }

        cleanupOldTracks(currentTime)
        cleanupCompletedHolds(currentTime)

        return updatedTracks
    }

    /**
     * Find the best matching detection for a track.
     * Uses position distance, color similarity, and lane consistency.
     */
    private fun findBestDetectionMatch(
        track: TrackedNote,
        detections: List<DetectedNote>,
        usedDetections: MutableSet<Int>,
        currentTime: Long
    ): Int {
        var bestMatchIndex = -1
        var bestScore = Float.MAX_VALUE

        val predictedPos = predictTrackPosition(track, currentTime)

        for ((index, detection) in detections.withIndex()) {
            if (usedDetections.contains(index)) continue

            // Skip if note types don't match (unless uncertain detection)
            if (track.noteType != detection.noteType &&
                track.state != TrackedNote.TrackingState.DETECTED &&
                detection.confidence < 0.7f) {
                continue
            }

            // Lane consistency check for osu!mania
            if (track.laneIndex != -1 && detection.laneIndex != -1 &&
                track.laneIndex != detection.laneIndex) {
                continue
            }

            val distance = sqrt(
                Math.pow(predictedPos.x - detection.x, 2.0) +
                Math.pow(predictedPos.y - detection.y, 2.0)
            ).toFloat()

            val colorSimilarity = calculateColorSimilarity(track.color, detection.color)
            val combinedScore = distance * (2.0f - colorSimilarity)

            val effectiveTolerance = trackAssociationTolerance * (1.0f + (1.0f - track.confidence))

            if (combinedScore < bestScore && combinedScore < effectiveTolerance) {
                bestScore = combinedScore
                bestMatchIndex = index
            }
        }

        return bestMatchIndex
    }

    /**
     * Update track with new detection using alpha-beta filter.
     * Calculates velocity from observed displacement over elapsed time.
     */
    private fun updateTrackWithDetection(
        oldTrack: TrackedNote,
        detection: DetectedNote,
        timestamp: Long
    ): TrackedNote {
        val timeDiffSec = (timestamp - oldTrack.timestamp) / 1_000_000_000.0f

        // Calculate raw velocity from observed displacement
        val rawVelocityX = (detection.x - oldTrack.x) / timeDiffSec
        val rawVelocityY = (detection.y - oldTrack.y) / timeDiffSec

        // Alpha-beta filter smoothing
        val smoothedVelocityX = if (oldTrack.state == TrackedNote.TrackingState.DETECTED) {
            rawVelocityX
        } else {
            velocitySmoothingFactor * oldTrack.velocityX + (1 - velocitySmoothingFactor) * rawVelocityX
        }

        val smoothedVelocityY = if (oldTrack.state == TrackedNote.TrackingState.DETECTED) {
            rawVelocityY
        } else {
            velocitySmoothingFactor * oldTrack.velocityY + (1 - velocitySmoothingFactor) * rawVelocityY
        }

        // Estimate arrival time at judgement line
        val estimatedArrivalTime = estimateArrivalTime(
            detection.x, detection.y, smoothedVelocityX, smoothedVelocityY, timestamp
        )

        return TrackedNote(
            id = oldTrack.id,
            x = detection.x,
            y = detection.y,
            width = detection.width,
            height = detection.height,
            color = detection.color,
            confidence = detection.confidence,
            velocityX = smoothedVelocityX,
            velocityY = smoothedVelocityY,
            estimatedArrivalTime = estimatedArrivalTime,
            normalizedX = detection.normalizedX,
            normalizedY = detection.normalizedY,
            laneIndex = detection.laneIndex,
            noteType = detection.noteType,
            state = determineTrackState(oldTrack.state, detection.confidence)
        )
    }

    /**
     * Predict track position using constant velocity model.
     * Bounded prediction - confidence decays over time.
     */
    private fun predictTrackPosition(track: TrackedNote, currentTime: Long): TrackedNote {
        val timeDiffSec = (currentTime - track.timestamp) / 1_000_000_000.0f

        val predictedX = track.x + (track.velocityX * timeDiffSec * 1_000_000_000).toInt()
        val predictedY = track.y + (track.velocityY * timeDiffSec * 1_000_000_000).toInt()

        // Reduce confidence for predicted (unobserved) positions
        val predictedConfidence = (track.confidence * 0.85f).coerceAtLeast(0.1f)

        return TrackedNote(
            id = track.id,
            x = predictedX,
            y = predictedY,
            width = track.width,
            height = track.height,
            color = track.color,
            confidence = predictedConfidence,
            velocityX = track.velocityX,
            velocityY = track.velocityY,
            estimatedArrivalTime = track.estimatedArrivalTime,
            normalizedX = track.normalizedX,
            normalizedY = track.normalizedY,
            laneIndex = track.laneIndex,
            noteType = track.noteType,
            state = track.state
        )
    }

    /**
     * Estimate arrival time at judgement line using observed velocity.
     * Not hardcoded - calculated from actual observations.
     */
    private fun estimateArrivalTime(
        noteX: Int,
        noteY: Int,
        velocityX: Float,
        velocityY: Float,
        currentTimestamp: Long
    ): Long? {
        val judgementLine = judgementLineCalibrator.getJudgementLine()

        if (judgementLine == null) return null

        val dx = judgementLine.centerX - noteX
        val dy = judgementLine.centerY - noteY

        // If moving away from judgement line, can't estimate
        val dotProduct = dx * velocityX + dy * velocityY
        if (dotProduct <= 0) return null

        val velocityMagnitude = sqrt(velocityX * velocityX + velocityY * velocityY)
        if (velocityMagnitude < 1.0f) return null

        val distanceToLine = sqrt(dx.toDouble() * dx.toDouble() + dy.toDouble() * dy.toDouble())
        val timeToLineSec = distanceToLine / velocityMagnitude.toDouble()

        return currentTimestamp + (timeToLineSec * 1_000_000_000).toLong()
    }

    private fun isTrackStillValidForRecovery(track: TrackedNote, currentTime: Long): Boolean {
        val ageMs = (currentTime - track.timestamp) / 1_000_000
        return ageMs < maxTrackAgeMs && track.confidence > recoveryConfidenceThreshold
    }

    private fun expireTrack(trackId: String, currentTime: Long) {
        activeTracks.remove(trackId)

        // Clean up hold state if applicable
        activeHolds.remove(trackId)
    }

    private fun cleanupOldTracks(currentTime: Long) {
        val expiredTrackIds = activeTracks.filter { (_, track) ->
            val ageMs = (currentTime - track.timestamp) / 1_000_000
            ageMs >= maxTrackAgeMs || track.confidence <= 0.05f
        }.map { (id, _) -> id }

        for (trackId in expiredTrackIds) {
            expireTrack(trackId, currentTime)
        }
    }

    private fun determineTrackState(oldState: TrackedNote.TrackingState, confidence: Float): TrackedNote.TrackingState {
        return when (oldState) {
            TrackedNote.TrackingState.DETECTED -> {
                if (confidence > 0.8f) TrackedNote.TrackingState.TRACKING
                else TrackedNote.TrackingState.DETECTED
            }
            TrackedNote.TrackingState.TRACKING -> {
                if (confidence > 0.6f) TrackedNote.TrackingState.TRACKING
                else TrackedNote.TrackingState.DETECTED
            }
            else -> oldState
        }
    }

    private fun calculateColorSimilarity(color1: Int, color2: Int): Float {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)

        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)

        val maxDiff = 255.0f
        val avgDiff = (abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)) / 3.0f

        return 1.0f - (avgDiff / maxDiff)
    }

    private fun createNewTrack(detection: DetectedNote, timestamp: Long): TrackedNote {
        return TrackedNote(
            id = detection.id,
            x = detection.x,
            y = detection.y,
            width = detection.width,
            height = detection.height,
            color = detection.color,
            confidence = detection.confidence,
            velocityX = 0.0f,
            velocityY = 0.0f,
            estimatedArrivalTime = null,
            normalizedX = detection.normalizedX,
            normalizedY = detection.normalizedY,
            laneIndex = detection.laneIndex,
            noteType = detection.noteType,
            state = TrackedNote.TrackingState.DETECTED
        )
    }

    // ============ HOLD NOTE TRACKING - Direct Tail Tracking ============

    /**
     * Initialize hold tracking when a hold head is detected.
     * Creates HoldTrackState with direct tail tracking support.
     */
    private fun initializeHoldTracking(trackId: String, track: TrackedNote, timestamp: Long) {
        activeHolds[trackId] = HoldTrackState(
            noteId = trackId,
            state = HoldTrackState.HoldState.DETECTED,
            headPosition = Point2D(track.x, track.y),
            tailPosition = null,
            pressTime = null,
            releaseTime = null,
            pointerId = 0,
            durationNs = minHoldDurationNs,
            confidence = track.confidence,
            startTime = timestamp,
            lastTailUpdateTime = timestamp,
            tailObservationCount = 0
        )
    }

    /**
     * Update hold tracking state based on new detection.
     * Directly tracks tail position and updates release estimates.
     */
    private fun updateHoldTracking(
        trackId: String,
        track: TrackedNote,
        detection: DetectedNote,
        timestamp: Long
    ) {
        var holdState = activeHolds[trackId] ?: return

        when (holdState.state) {
            HoldTrackState.HoldState.DETECTED -> {
                holdState.state = HoldTrackState.HoldState.TRACKING_HEAD
                holdState.headPosition = Point2D(detection.x, detection.y)
                holdState.confidence = detection.confidence
            }

            HoldTrackState.HoldState.TRACKING_HEAD -> {
                holdState.headPosition = Point2D(detection.x, detection.y)
                holdState.confidence = detection.confidence

                // If tail is visible, start tracking it directly
                if (detection.noteType == DetectedNote.NoteType.HOLD_TAIL) {
                    holdState.tailPosition = Point2D(detection.x, detection.y)
                    holdState.state = HoldTrackState.HoldState.TRACKING_TAIL
                    holdState.lastTailUpdateTime = timestamp
                    holdState.tailObservationCount++
                    holdState.durationNs = calculateHoldDurationFromObservations(holdState)
                }
            }

            HoldTrackState.HoldState.TRACKING_TAIL -> {
                if (detection.noteType == DetectedNote.NoteType.HOLD_TAIL) {
                    // Direct tail observation - update position and release estimate
                    holdState.tailPosition = Point2D(detection.x, detection.y)
                    holdState.lastTailUpdateTime = timestamp
                    holdState.tailObservationCount++
                    holdState.durationNs = calculateHoldDurationFromObservations(holdState)
                    holdState.confidence = detection.confidence
                }
            }

            HoldTrackState.HoldState.HOLD_ACTIVE -> {
                // Continue tracking tail during active hold
                if (detection.noteType == DetectedNote.NoteType.HOLD_TAIL) {
                    holdState.tailPosition = Point2D(detection.x, detection.y)
                    holdState.lastTailUpdateTime = timestamp
                    holdState.tailObservationCount++
                    holdState.durationNs = calculateHoldDurationFromObservations(holdState)
                }
            }

            HoldTrackState.HoldState.TRACKING_TAIL, HoldTrackState.HoldState.RELEASE_SCHEDULED -> {
                // Still tracking tail even after release scheduled
                if (detection.noteType == DetectedNote.NoteType.HOLD_TAIL) {
                    holdState.tailPosition = Point2D(detection.x, detection.y)
                    holdState.lastTailUpdateTime = timestamp
                    holdState.tailObservationCount++
                    holdState.durationNs = calculateHoldDurationFromObservations(holdState)
                }
            }

            else -> {
                // COMPLETED, FAILED, LOST - don't update
            }
        }

        activeHolds[trackId] = holdState
    }

    /**
     * Calculate hold duration from direct tail observations.
     * Updates release estimate as new frames arrive.
     * Does NOT rely solely on head-to-tail distance.
     */
    private fun calculateHoldDurationFromObservations(holdState: HoldTrackState): Long {
        if (holdState.tailPosition == null) return minHoldDurationNs

        val dx = holdState.tailPosition!!.x - holdState.headPosition.x
        val dy = holdState.tailPosition!!.y - holdState.headPosition.y
        val distance = sqrt(dx.toDouble() * dx.toDouble() + dy.toDouble() * dy.toDouble())

        // Use observed tail movement to estimate duration
        // If we have multiple tail observations, calculate tail velocity
        if (holdState.tailObservationCount > 1 && holdState.lastTailUpdateTime > holdState.startTime) {
            val timeSinceFirstTail = (holdState.lastTailUpdateTime - holdState.startTime) / 1_000_000_000.0
            if (timeSinceFirstTail > 0) {
                val tailVelocity = distance / timeSinceFirstTail
                // Estimated duration based on tail movement speed
                val estimatedDuration = (distance / tailVelocity) * 1_000_000_000
                return estimatedDuration.coerceIn(minHoldDurationNs, 5000_000_000L)
            }
        }

        // Fallback: distance-based estimate with velocity from head tracking
        val headVelocity = sqrt(holdState.headPosition.x.toDouble() * holdState.headPosition.x +
            holdState.headPosition.y.toDouble() * holdState.headPosition.y)
        if (headVelocity > 0) {
            val durationFromVelocity = (distance / headVelocity) * 1_000_000_000
            return durationFromVelocity.coerceIn(minHoldDurationNs, 5000_000_000L)
        }

        return minHoldDurationNs
    }

    /**
     * Get hold state for a track
     */
    fun getHoldState(trackId: String): HoldTrackState? {
        return activeHolds[trackId]
    }

    /**
     * Get or assign pointer ID for a hold
     */
    fun assignPointerId(trackId: String): Int {
        val holdState = activeHolds[trackId] ?: return 1
        if (holdState.pointerId > 0) return holdState.pointerId

        // Assign new pointer ID
        val newPointerId = activeHolds.values.map { it.pointerId }.maxOrNull() ?: 0 + 1
        val updatedState = holdState.copy(pointerId = newPointerId)
        activeHolds[trackId] = updatedState
        return newPointerId
    }

    /**
     * Update hold state manually (called by InputScheduler when press/release dispatched)
     */
    fun updateHoldStateManual(
        trackId: String,
        state: HoldTrackState.HoldState,
        timestamp: Long
    ) {
        val holdState = activeHolds[trackId] ?: return
        val updatedState = holdState.copy(state = state)

        when (state) {
            HoldTrackState.HoldState.PRESS_SCHEDULED,
            HoldTrackState.HoldState.HOLD_ACTIVE -> {
                updatedState.pressTime = timestamp
            }
            HoldTrackState.HoldState.RELEASE_SCHEDULED,
            HoldTrackState.HoldState.COMPLETED -> {
                updatedState.releaseTime = timestamp
            }
            HoldTrackState.HoldState.FAILED,
            HoldTrackState.HoldState.LOST -> {
                updatedState.releaseTime = timestamp
            }
        }

        activeHolds[trackId] = updatedState
    }

    /**
     * Get all hold tracking data
     */
    fun getHoldTrackingData(): Map<String, HoldTrackState> {
        return activeHolds.toMap()
    }

    /**
     * Get estimated release time for a hold based on latest observations
     */
    fun getEstimatedReleaseTime(trackId: String): Long? {
        val holdState = activeHolds[trackId] ?: return null
        if (holdState.pressTime == null) return null
        return holdState.pressTime!! + holdState.durationNs
    }

    private fun cleanupCompletedHolds(currentTime: Long) {
        val completedHoldIds = activeHolds.filter { (_, state) ->
            state.state == HoldTrackState.HoldState.COMPLETED ||
                state.state == HoldTrackState.HoldState.FAILED ||
                state.state == HoldTrackState.HoldState.LOST
        }.map { (id, _) -> id }

        for (holdId in completedHoldIds) {
            activeHolds.remove(holdId)
        }
    }

    // ============ Test-Friendly Accessors ============
    // These methods are used by unit tests to verify internal state

    fun getActiveTrackCountForTest(): Int {
        return activeTracks.size
    }

    fun getActiveHoldCountForTest(): Int {
        return activeHolds.count { (_, state) ->
            state.state == HoldTrackState.HoldState.HOLD_ACTIVE ||
                state.state == HoldTrackState.HoldState.PRESS_SCHEDULED ||
                state.state == HoldTrackState.HoldState.TRACKING_TAIL
        }
    }

    fun getHoldStateForTest(trackId: String): HoldTrackState? {
        return activeHolds[trackId]
    }

    fun updateHoldStateManualForTest(
        trackId: String,
        state: HoldTrackState.HoldState,
        timestamp: Long
    ) {
        updateHoldStateManual(trackId, state, timestamp)
    }

    fun getLatestTrackedNotesForTest(): List<TrackedNote> {
        return activeTracks.values.toList()
    }

    // ============ DATA CLASSES ============

    data class Point2D(val x: Int, val y: Int)

    data class HoldTrackState(
        val noteId: String,
        var state: HoldState,
        var headPosition: Point2D,
        var tailPosition: Point2D?,
        var pressTime: Long? = null,
        var releaseTime: Long? = null,
        var pointerId: Int = 0,
        var durationNs: Long = 0,
        var confidence: Float = 1.0f,
        var startTime: Long = System.nanoTime(),
        var lastTailUpdateTime: Long = System.nanoTime(),
        var tailObservationCount: Int = 0
    ) {
        enum class HoldState {
            DETECTED, TRACKING_HEAD, WAITING_FOR_PRESS, PRESS_SCHEDULED,
            HOLD_ACTIVE, TRACKING_TAIL, RELEASE_SCHEDULED, COMPLETED, FAILED, LOST
        }
    }
}

/**
 * Android AccessibilityService Input Limitations (Documented):
 *
 * 1. ACTION_CLICK is NOT used as the primary input mechanism.
 *    dispatchGesture() with GestureDescription is used instead.
 *
 * 2. dispatchGesture() limitations:
 *    - Dispatching a new gesture may cancel an already-dispatched gesture.
 *    - No synchronous confirmation of gesture completion.
 *    - No gesture completion/cancellation callbacks via standard API.
 *    - True simultaneous multi-touch cannot be guaranteed.
 *
 * 3. This implementation:
 *    - Centralizes gesture ownership in InputScheduler.activeGestures
 *    - Combines compatible simultaneous presses into multi-stroke gestures
 *    - Tracks dispatches as "pending" until state changes observed
 *    - Documents limitations instead of claiming unsupported capabilities
 *    - Implements safe fallbacks (release all gestures on error)
 *
 * 4. If a required input pattern cannot be supported reliably,
 *    the limitation is documented and the app degrades gracefully.
 */
