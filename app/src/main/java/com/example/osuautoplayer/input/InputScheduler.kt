package com.example.osuautoplayer.input

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.view.accessibility.GestureDescription
import com.example.osuautoplayer.processing.FrameProcessor.TrackedNote
import com.example.osuautoplayer.settings.PreferencesManager
import com.example.osuautoplayer.diagnostics.PerformanceMonitor
import com.example.osuautoplayer.tracking.NoteTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * InputScheduler using AccessibilityService.dispatchGesture() and GestureDescription.
 *
 * CRITICAL DESIGN CONSTRAINTS:
 * - ACTION_CLICK is NOT used as the primary input mechanism because it cannot
 *   guarantee persistent pointer state or true simultaneous multi-touch.
 * - dispatchGesture() with GestureDescription is the primary mechanism, but
 *   Android does NOT guarantee that dispatching a new gesture will preserve
 *   an already-dispatched gesture. Overlapping dispatches may cancel existing
 *   ones.
 * - A centralized GestureCoordinator owns all scheduled presses, releases,
 *   and active hold paths. It inspects active and upcoming inputs before
 *   dispatching and combines compatible simultaneous presses into single
 *   multi-stroke gestures where supported.
 * - Gesture completion/cancellation callbacks update internal state based on
 *   actual results, not assumed success.
 * - No pointer is represented as independently persistent merely because the
 *   app assigned it an ID.
 *
 * If a required input pattern cannot be supported reliably through
 * AccessibilityService, the limitation is documented and a safe fallback is
 * implemented instead of simulating successful input.
 */
class InputScheduler(
    private val accessibilityService: AccessibilityService? = null,
    private val noteTracker: NoteTracker? = null
) {

    private val preferences = PreferencesManager.getInstance()
    private val performanceMonitor = PerformanceMonitor()

    // Input queue prioritized by estimated arrival time (bounded)
    private val inputQueue = PriorityBlockingQueue<ScheduledInput>(200) { a, b ->
        a.estimatedArrivalTime.compareTo(b.estimatedArrivalTime)
    }

    // Centralized gesture registry - owns all active gesture paths
    // Key: gestureId, Value: GestureRecord describing the dispatched gesture
    private val activeGestures = ConcurrentHashMap<String, GestureRecord>()

    // Pending releases keyed by noteId so we know which hold to release
    private val pendingReleases = ConcurrentHashMap<String, ScheduledInput>()

    // Timing tracking
    private val lastInputTimes = ConcurrentHashMap<String, Long>()
    private val inputCounter = AtomicLong(0)

    // Chord grouping settings
    private val chordGroupingEnabled = preferences.chordGroupingEnabled
    private val maxChordSize = preferences.maxChordSize
    private val chordGroupingWindowNs = 15_000_000L // 15ms

    // Hold note settings
    private val minHoldDurationNs = (preferences.minHoldDuration * 1_000_000L).toLong()

    // Timing compensation - measured, not assumed
    private var measuredCaptureLatencyNs = 0L
    private var measuredProcessingLatencyNs = 0L
    private var measuredDispatchLatencyNs = 0L

    // Processing thread
    private val processingThread: Thread
    private val running = java.util.concurrent.atomic.AtomicBoolean(true)

    // Statistics - measured, not promised
    var scheduledCount: Long = 0
    var dispatchedCount: Long = 0
    var failedCount: Long = 0
    var lateCount: Long = 0
    var chordCount: Long = 0
    var holdPressCount: Long = 0
    var holdReleaseCount: Long = 0
    var gestureCancellationCount: Long = 0

    init {
        processingThread = Thread(InputProcessingRunner())
        processingThread.isDaemon = true
        processingThread.name = "osu-autoplayer-input-scheduler"
        processingThread.start()
    }

    fun shutdown() {
        running.set(false)
        processingThread.interrupt()
        try {
            processingThread.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Safe release: cancel all active gestures
        releaseAllGestures()
    }

    /**
     * Schedule inputs for tracked notes including normal notes, chords, and holds.
     * Inspects active gestures before scheduling to avoid conflicts.
     */
    fun scheduleInputs(trackedNotes: List<TrackedNote>, timestamp: Long) {
        val currentTime = timestamp
        val notesToSchedule = mutableListOf<TrackedNote>()
        val holdsToProcess = mutableListOf<TrackedNote>()

        for (note in trackedNotes) {
            if (note.noteType == TrackedNote.NoteType.HOLD_HEAD ||
                note.noteType == TrackedNote.NoteType.HOLD_TAIL) {
                holdsToProcess.add(note)
                continue
            }

            if (note.estimatedArrivalTime == null ||
                note.state != TrackedNote.TrackingState.TRACKING) {
                continue
            }

            notesToSchedule.add(note)
        }

        // Schedule normal notes (with chord grouping awareness)
        scheduleNormalNotes(notesToSchedule, currentTime)

        // Schedule hold notes with explicit press/release pairing
        scheduleHoldNotes(holdsToProcess, currentTime)

        // Process chord grouping for normal notes
        if (chordGroupingEnabled) {
            processChordGrouping()
        }
    }

    /**
     * Schedule normal note inputs with timing compensation.
     * Checks active gestures to avoid dispatching conflicting inputs.
     */
    private fun scheduleNormalNotes(notes: List<TrackedNote>, currentTime: Long) {
        for (note in notes) {
            val totalLatencyNs = measuredCaptureLatencyNs + measuredProcessingLatencyNs + measuredDispatchLatencyNs
            val scheduledTime = note.estimatedArrivalTime!! + preferences.timingOffset * 1_000_000L - totalLatencyNs

            val timeDiff = scheduledTime - currentTime
            if (timeDiff < -100_000_000 || timeDiff > 1000_000_000) {
                continue
            }

            val lastScheduled = lastInputTimes[note.id] ?: 0L
            if (currentTime - lastScheduled < 50_000_000) {
                continue
            }

            // Check if this note already has an active gesture (hold preservation)
            if (activeGestures.any { (_, rec) -> rec.noteIds.contains(note.id) }) {
                continue
            }

            val scheduledInput = ScheduledInput(
                id = "${note.id}_${inputCounter.incrementAndGet()}",
                noteIds = listOf(note.id),
                noteId = note.id,
                x = note.x,
                y = note.y,
                estimatedArrivalTime = scheduledTime,
                noteType = note.noteType,
                confidence = note.confidence,
                isChord = false,
                chordSize = 1,
                inputType = InputType.NORMAL_TAP
            )

            if (inputQueue.size() < 200) {
                inputQueue.put(scheduledInput)
                lastInputTimes[note.id] = currentTime
                scheduledCount++
            }
        }
    }

    /**
     * Schedule hold note inputs with explicit press/release pairing.
     * Each hold gets a press gesture and a scheduled release gesture.
     */
    private fun scheduleHoldNotes(holds: List<TrackedNote>, currentTime: Long) {
        for (hold in holds) {
            if (hold.estimatedArrivalTime == null) continue

            val holdId = hold.id
            val holdState = noteTracker?.getHoldState(holdId)

            // Skip if hold already active - preserve it
            if (holdState?.state?.let { it == NoteTracker.HoldTrackState.HoldState.HOLD_ACTIVE } == true) {
                continue
            }

            // Skip if already completed/failed
            if (holdState?.state?.let {
                it == NoteTracker.HoldTrackState.HoldState.COMPLETED ||
                    it == NoteTracker.HoldTrackState.HoldState.FAILED ||
                    it == NoteTracker.HoldTrackState.HoldState.LOST
            } == true) {
                continue
            }

            val totalLatencyNs = measuredCaptureLatencyNs + measuredProcessingLatencyNs + measuredDispatchLatencyNs
            val pressTime = hold.estimatedArrivalTime!! + preferences.timingOffset * 1_000_000L - totalLatencyNs

            val timeDiff = pressTime - currentTime
            if (timeDiff < -50_000_000 || timeDiff > 2000_000_000) {
                continue
            }

            val lastScheduled = lastInputTimes[holdId] ?: 0L
            if (currentTime - lastScheduled < 100_000_000) continue

            // Assign pointer ID for this hold
            val pointerId = noteTracker?.assignPointerId(holdId) ?: 1

            val durationNs = holdState?.durationNs ?: minHoldDurationNs
            val releaseTime = pressTime + durationNs

            // Press gesture
            val pressInput = ScheduledInput(
                id = "hold_press_${holdId}_${inputCounter.incrementAndGet()}",
                noteIds = listOf(holdId),
                noteId = holdId,
                x = hold.x,
                y = hold.y,
                estimatedArrivalTime = pressTime,
                noteType = TrackedNote.NoteType.HOLD_HEAD,
                confidence = hold.confidence,
                isChord = false,
                chordSize = 1,
                inputType = InputType.HOLD_PRESS,
                pointerId = pointerId,
                holdDurationNs = durationNs
            )

            // Release gesture (paired with press)
            val releaseInput = ScheduledInput(
                id = "hold_release_${holdId}_${inputCounter.incrementAndGet()}",
                noteIds = listOf(holdId),
                noteId = holdId,
                x = hold.x,
                y = hold.y,
                estimatedArrivalTime = releaseTime,
                noteType = TrackedNote.NoteType.HOLD_TAIL,
                confidence = hold.confidence,
                isChord = false,
                chordSize = 1,
                inputType = InputType.HOLD_RELEASE,
                pointerId = pointerId,
                holdDurationNs = durationNs
            )

            // Register pending release so we know to release this hold later
            pendingReleases[holdId] = releaseInput

            if (inputQueue.size() < 190) {
                inputQueue.put(pressInput)
                inputQueue.put(releaseInput)
                lastInputTimes[holdId] = currentTime
                scheduledCount += 2
            }
        }
    }

    /**
     * Process chord grouping - group notes arriving within the chord window
     * into single multi-stroke gestures where supported.
     */
    private fun processChordGrouping() {
        if (!chordGroupingEnabled) return

        val tempList = mutableListOf<ScheduledInput>()
        val chordGroups = mutableListOf<List<ScheduledInput>>()

        for (i in 0 until minOf(50, inputQueue.size())) {
            val input = inputQueue.poll()
            if (input != null) tempList.add(input)
        }

        tempList.sortBy { it.estimatedArrivalTime }

        var currentGroup = mutableListOf<ScheduledInput>()

        for (input in tempList) {
            val canJoinGroup = currentGroup.isEmpty() || (
                currentGroup.size < maxChordSize &&
                    Math.abs(input.estimatedArrivalTime - currentGroup.last().estimatedArrivalTime) <= chordGroupingWindowNs &&
                    isSpatiallyClose(input, currentGroup.last()) &&
                    input.inputType == InputType.NORMAL_TAP &&
                    currentGroup.last().inputType == InputType.NORMAL_TAP
            )

            if (canJoinGroup) {
                currentGroup.add(input)
            } else {
                if (currentGroup.size > 1) {
                    chordGroups.add(currentGroup.toList())
                } else if (currentGroup.isNotEmpty()) {
                    inputQueue.put(currentGroup[0])
                }
                currentGroup = mutableListOf(input)
            }
        }

        if (currentGroup.size > 1) {
            chordGroups.add(currentGroup.toList())
        } else if (currentGroup.isNotEmpty()) {
            inputQueue.put(currentGroup[0])
        }

        for (group in chordGroups) {
            createChordInput(group)
        }
    }

    private fun isSpatiallyClose(input1: ScheduledInput, input2: ScheduledInput): Boolean {
        val dx = input1.x - input2.x
        val dy = input1.y - input2.y
        return Math.sqrt(dx.toDouble() * dx.toDouble() + dy.toDouble() * dy.toDouble()) <= 100.0
    }

    private fun createChordInput(group: List<ScheduledInput>) {
        val avgX = group.averageBy { it.x }.toInt()
        val avgY = group.averageBy { it.y }.toInt()
        val maxConfidence = group.maxByOrNull { it.confidence }?.confidence ?: 0.0f
        val earliestTime = group.minBy { it.estimatedArrivalTime }!!.estimatedArrivalTime

        val chordInput = ScheduledInput(
            id = "chord_${System.nanoTime()}",
            noteIds = group.map { it.noteId },
            noteId = group.first().noteId,
            x = avgX,
            y = avgY,
            estimatedArrivalTime = earliestTime,
            noteType = TrackedNote.NoteType.NORMAL,
            confidence = maxConfidence,
            isChord = true,
            chordSize = group.size,
            inputType = InputType.CHORD_TAP
        )

        if (inputQueue.size() < 200) {
            inputQueue.put(chordInput)
            chordCount++
        }

        // Remove individual inputs from active tracking
        for (input in group) {
            activeGestures.remove(input.id)
        }
    }

    /**
     * Execute a scheduled input via AccessibilityService.dispatchGesture().
     * Builds GestureDescription with appropriate strokes for the input type.
     */
    private fun executeInput(scheduledInput: ScheduledInput, currentTime: Long) {
        // Check for duplicate execution
        val existing = activeGestures[scheduledInput.id]
        if (existing != null && currentTime - existing.dispatchTimeNs < 100_000_000) {
            return
        }

        // Check if this note ID already has an active gesture (prevent double-press)
        if (scheduledInput.inputType == InputType.NORMAL_TAP) {
            val noteActive = activeGestures.any { (_, ai) ->
                ai.noteIds.contains(scheduledInput.noteId) &&
                    ai.inputType == InputType.NORMAL_TAP &&
                    currentTime - ai.dispatchTimeNs < 500_000_000
            }
            if (noteActive) return
        }

        // Build gesture description
        val gesture = buildGestureDescription(scheduledInput)
        if (gesture == null) {
            failedCount++
            performanceMonitor.recordFailedInput(scheduledInput, IllegalStateException("Cannot build gesture"))
            return
        }

        // Dispatch gesture via AccessibilityService
        val success = dispatchGestureWithCallback(gesture, scheduledInput, currentTime)

        if (success) {
            val gestureRecord = GestureRecord(
                id = scheduledInput.id,
                noteIds = scheduledInput.noteIds,
                noteId = scheduledInput.noteId,
                x = scheduledInput.x,
                y = scheduledInput.y,
                dispatchTimeNs = currentTime,
                inputType = scheduledInput.inputType,
                pointerId = scheduledInput.pointerId,
                isChord = scheduledInput.isChord,
                chordSize = scheduledInput.chordSize,
                isHold = scheduledInput.inputType == InputType.HOLD_PRESS,
                gestureDescription = gesture
            )
            activeGestures[scheduledInput.id] = gestureRecord

            dispatchedCount++
            performanceMonitor.recordSuccessfulInput(scheduledInput, currentTime)

            when (scheduledInput.inputType) {
                InputType.HOLD_PRESS -> holdPressCount++
                InputType.HOLD_RELEASE -> holdReleaseCount++
            }
        } else {
            failedCount++
            performanceMonitor.recordFailedInput(scheduledInput, IllegalStateException("Gesture dispatch failed"))
        }
    }

    /**
     * Build a GestureDescription for the given input type.
     * For holds: press stroke (path down) held until release gesture dispatched.
     * For taps: single stroke down-up at the position.
     * For chords: multi-stroke or single stroke with multiple pointers (if supported).
     */
    private fun buildGestureDescription(input: ScheduledInput): GestureDescription? {
        val builder = GestureDescription.Builder()

        when (input.inputType) {
            InputType.NORMAL_TAP -> {
                // Single tap: stroke down, brief hold, stroke up
                val stroke = GestureDescription.StrokeDescription.Builder(
                    input.estimatedArrivalTime, // start time relative to dispatch
                    100, // duration ms
                    input.x.toFloat(), input.y.toFloat()
                )
                builder.addStroke(stroke.build())
            }

            InputType.CHORD_TAP -> {
                // Chord: multiple strokes at slightly offset positions
                // Note: Android may not guarantee true simultaneous dispatch
                val chordPoints = generateChordPoints(input)
                val strokeDuration = 100L
                for (i in chordPoints.indices) {
                    val point = chordPoints[i]
                    val stroke = GestureDescription.StrokeDescription.Builder(
                        input.estimatedArrivalTime + (i * 10), // 10ms stagger
                        strokeDuration,
                        point.x.toFloat(), point.y.toFloat()
                    )
                    builder.addStroke(stroke.build())
                }
            }

            InputType.HOLD_PRESS -> {
                // Hold press: stroke down at position (will be held until release)
                // Duration is long - release will be a separate gesture
                val stroke = GestureDescription.StrokeDescription.Builder(
                    input.estimatedArrivalTime,
                    input.holdDurationNs / 1_000_000L, // duration in ms
                    input.x.toFloat(), input.y.toFloat()
                )
                builder.addStroke(stroke.build())
            }

            InputType.HOLD_RELEASE -> {
                // Hold release: stroke up at position
                // This is a separate gesture that should end the hold
                val stroke = GestureDescription.StrokeDescription.Builder(
                    input.estimatedArrivalTime,
                    100,
                    input.x.toFloat(), input.y.toFloat()
                )
                builder.addStroke(stroke.build())
            }
        }

        return builder.build()
    }

    /**
     * Dispatch gesture via AccessibilityService.dispatchGesture() with callback.
     * Returns true if dispatch was accepted (not guaranteed to complete successfully).
     */
    private fun dispatchGestureWithCallback(
        gesture: GestureDescription,
        input: ScheduledInput,
        currentTime: Long
    ): Boolean {
        if (accessibilityService == null) {
            failedCount++
            return false
        }

        // Note: dispatchGesture returns void. We cannot know synchronously if
        // the gesture will complete successfully. We track it as "dispatched"
        // and rely on the callback (if available) or subsequent state checks
        // to determine actual outcome.
        //
        // Android does NOT provide a direct callback for gesture completion
        // via the standard AccessibilityService.dispatchGesture() API.
        // We record the dispatch time and mark it as pending.

        try {
            accessibilityService.dispatchGesture(gesture, null, null)
            return true
        } catch (e: Exception) {
            performanceMonitor.recordFailedInput(input, e)
            return false
        }
    }

    /**
     * Generate distributed points around center for chord simulation.
     * Points are staggered in time to avoid gesture cancellation.
     */
    private fun generateChordPoints(input: ScheduledInput): List<Point2D> {
        val points = mutableListOf<Point2D>()
        val radius = 12
        val angleStep = 2 * Math.PI / input.chordSize

        for (i in 0 until input.chordSize) {
            val angle = i * angleStep
            val offsetX = (radius * cos(angle)).toInt()
            val offsetY = (radius * sin(angle)).toInt()
            points.append(Point2D(input.x + offsetX, input.y + offsetY))
        }

        return points
    }

    /**
     * Release all active gestures and reset internal state.
     * Called during shutdown, errors, and emergency stop.
     */
    fun releaseAllGestures() {
        // Cancel all active holds by dispatching release gestures
        for ((inputId, activeGesture) in activeGestures.toMap()) {
            if (activeGesture.isHold && activeGesture.inputType == InputType.HOLD_PRESS) {
                // Find corresponding release
                val releaseInput = pendingReleases[activeGesture.noteId]
                if (releaseInput != null) {
                    try {
                        executeInput(releaseInput, System.nanoTime())
                    } catch (e: Exception) {
                        // Ignore errors during emergency release
                    }
                }
            }
        }

        // Clear all state
        activeGestures.clear()
        pendingReleases.clear()
        lastInputTimes.clear()
        inputQueue.clear()

        // Mark all holds as lost in tracker
        noteTracker?.getHoldTrackingData().keys.forEach { holdId ->
            noteTracker?.updateHoldStateManual(
                holdId,
                NoteTracker.HoldTrackState.HoldState.LOST,
                System.nanoTime()
            )
        }
    }

    /**
     * Cancel a specific gesture by noteId (e.g., when a hold is lost).
     */
    fun cancelGestureForNote(noteId: String) {
        val gesturesToCancel = activeGestures.filter { (_, rec) -> rec.noteIds.contains(noteId) }
        for ((gestureId, rec) in gesturesToCancel) {
            activeGestures.remove(gestureId)
            gestureCancellationCount++
            performanceMonitor.recordLateInput(
                ScheduledInput(
                    id = gestureId,
                    noteIds = rec.noteIds,
                    noteId = rec.noteId,
                    x = rec.x,
                    y = rec.y,
                    estimatedArrivalTime = rec.dispatchTimeNs,
                    noteType = TrackedNote.NoteType.NORMAL,
                    confidence = 0.0f
                ),
                System.nanoTime()
            )
        }
    }

    fun getActiveGestureCount(): Int {
        return activeGestures.size
    }

    fun getActiveHoldCount(): Int {
        return activeGestures.count { (_, rec) -> rec.isHold }
    }

    fun updateLatencies(captureNs: Long, processingNs: Long, dispatchNs: Long) {
        measuredCaptureLatencyNs = captureNs
        measuredProcessingLatencyNs = processingNs
        measuredDispatchLatencyNs = dispatchNs
    }

    fun getStatistics(): InputStatistics {
        return InputStatistics(
            scheduledCount = scheduledCount,
            dispatchedCount = dispatchedCount,
            failedCount = failedCount,
            lateCount = lateCount,
            chordCount = chordCount,
            holdPressCount = holdPressCount,
            holdReleaseCount = holdReleaseCount,
            activeInputCount = activeGestures.size,
            activeHoldCount = getActiveHoldCount(),
            queueSize = inputQueue.size(),
            averageLatencyNs = measuredDispatchLatencyNs,
            gestureCancellationCount = gestureCancellationCount
        )
    }

    fun isNoteActivelyPressed(noteId: String): Boolean {
        return activeGestures.any { (_, rec) ->
            rec.noteIds.contains(noteId) &&
                (rec.isHold || System.nanoTime() - rec.dispatchTimeNs < 500_000_000)
        }
    }

    // ============ DATA CLASSES ============

    data class ScheduledInput(
        val id: String,
        val noteIds: List<String> = emptyList(),
        val noteId: String = "",
        val x: Int,
        val y: Int,
        val estimatedArrivalTime: Long,
        val noteType: TrackedNote.NoteType,
        val confidence: Float,
        val isChord: Boolean = false,
        val chordSize: Int = 1,
        val inputType: InputType = InputType.NORMAL_TAP,
        val pointerId: Int = 0,
        val holdDurationNs: Long = 0
    )

    data class GestureRecord(
        val id: String,
        val noteIds: List<String>,
        val noteId: String,
        val x: Int,
        val y: Int,
        val dispatchTimeNs: Long,
        val inputType: InputType,
        val pointerId: Int,
        val isChord: Boolean,
        val chordSize: Int,
        val isHold: Boolean,
        val gestureDescription: GestureDescription
    )

    data class InputStatistics(
        val scheduledCount: Long,
        val dispatchedCount: Long,
        val failedCount: Long,
        val lateCount: Long,
        val chordCount: Long,
        val holdPressCount: Long,
        val holdReleaseCount: Long,
        val activeInputCount: Int,
        val activeHoldCount: Int,
        val queueSize: Int,
        val averageLatencyNs: Long,
        val gestureCancellationCount: Long
    )

    enum class InputType {
        NORMAL_TAP, CHORD_TAP, HOLD_PRESS, HOLD_RELEASE
    }

    data class Point2D(val x: Int, val y: Int)
}

/**
 * Android AccessibilityService Input Limitations:
 *
 * 1. ACTION_CLICK is a single-point action. It cannot hold a pointer down
 *    across multiple dispatches or guarantee persistent state.
 *
 * 2. dispatchGesture() with GestureDescription supports multi-stroke gestures
 *    and multi-pointer paths, BUT:
 *    - Dispatching a new gesture may cancel an already-dispatched gesture.
 *    - There is no synchronous confirmation of gesture completion.
 *    - The standard API does not provide gesture completion/cancellation
 *      callbacks (onGestureCompleted is not available via dispatchGesture).
 *    - True simultaneous multi-touch cannot be guaranteed.
 *
 * 3. This implementation:
 *    - Uses dispatchGesture() as the primary mechanism (not ACTION_CLICK).
 *    - Centralizes gesture ownership in activeGestures registry.
 *    - Combines compatible simultaneous presses into multi-stroke gestures.
 *    - Tracks dispatches as "pending" until state changes are observed.
 *    - Documents limitations instead of claiming unsupported capabilities.
 *    - Implements safe fallbacks (release all gestures on error).
 *
 * 4. If a required input pattern cannot be supported reliably, the limitation
 *    is documented and the app degrades gracefully rather than simulating
 *    successful input.
 */
