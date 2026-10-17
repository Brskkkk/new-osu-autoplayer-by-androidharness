package com.example.osuautoplayer.detection

import android.graphics.Bitmap
import android.graphics.Color
import com.example.osuautoplayer.processing.FrameProcessor.DetectedNote
import com.example.osuautoplayer.settings.PreferencesManager
import com.example.osuautoplayer.calibration.JudgementLineCalibrator
import kotlin.math.sqrt

/**
 * NoteDetector - Detects osu! notes from captured frames.
 *
 * KEY DESIGN FIXES:
 * - Uses normalized playfield coordinates (0-1) instead of raw pixels
 * - Configurable detection features (color ranges, size ranges, lane restrictions)
 * - Integrates with SkinAdaptationManager for skin-aware detection
 * - Manual fallback when automatic detection confidence is low
 * - Restricted to osu!mania mode (vertical falling notes, lanes)
 * - Temporal consistency: rejects detections that don't match expected movement
 */
class NoteDetector(
    private val judgementLineCalibrator: JudgementLineCalibrator? = null
) {

    private val preferences = PreferencesManager.getInstance()

    // Default HSV color ranges (configurable via SkinAdaptationManager)
    private var normalNoteHueRanges: List<ColorRange> = defaultNormalNoteRanges
    private var holdNoteHueRanges: List<ColorRange> = defaultHoldNoteRanges

    // Detection thresholds
    private val minNoteSizePx: Int = 8
    private val maxNoteSizePx: Int = 64 // Scaled for higher resolutions
    private val minConfidenceThreshold: Float = 0.3f
    private val laneTolerancePx: Int = 30

    // Temporal consistency tracking
    private val recentDetections = mutableMapOf<String, DetectedNote>()
    private val maxDetectionAgeMs = 500L

    // osu!mania mode - explicitly restricted
    val gameMode: GameMode = GameMode.OSU_MANIA

    companion object {
        private val defaultNormalNoteRanges = listOf(
            ColorRange(0, 30, "Red notes"),
            ColorRange(120, 150, "Green notes"),
            ColorRange(200, 240, "Blue notes"),
            ColorRange(240, 260, "Purple notes")
        )

        private val defaultHoldNoteRanges = listOf(
            ColorRange(40, 80, "Yellow/Orange notes (holds)"),
            ColorRange(80, 100, "Light green notes")
        )
    }

    init {
        // Will be updated by SkinAdaptationManager when available
    }

    /**
     * Update color ranges from skin adaptation
     */
    fun updateColorRanges(normalRanges: List<ColorRange>, holdRanges: List<ColorRange>) {
        normalNoteHueRanges = normalRanges
        holdNoteHueRanges = holdRanges
    }

    /**
     * Detect notes from a captured frame.
     * Returns detections in both pixel coordinates and normalized playfield coordinates.
     */
    fun detectNotes(bitmap: Bitmap, timestamp: Long): List<DetectedNote> {
        val width = bitmap.width
        val height = bitmap.height
        val detectedNotes = mutableListOf<DetectedNote>()

        // Get judgement line for lane restrictions
        val judgementLine = judgementLineCalibrator?.getJudgementLine()
        val laneCenterX = judgementLine?.centerX ?: width / 2
        val laneWidth = judgementLine?.width ?: width

        // Sample pixels for efficiency
        val sampleRate = calculateSampleRate(width, height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val hsvValues = FloatArray(3)

        for (y in 0 until height step sampleRate) {
            for (x in 0 until width step sampleRate) {
                val pixelIndex = y * width + x
                val color = pixels[pixelIndex]

                Color.colorToHSV(color, hsvValues)
                val hue = hsvValues[0]
                val saturation = hsvValues[1]
                val value = hsvValues[2]

                // Skip low saturation or extreme brightness
                if (saturation < 0.3 || value < 0.2 || value > 0.9) continue

                // Check if pixel matches note colors
                val noteType = if (isNoteColor(hue, normalNoteHueRanges)) {
                    DetectedNote.NoteType.NORMAL
                } else if (isNoteColor(hue, holdNoteHueRanges)) {
                    DetectedNote.NoteType.HOLD_HEAD
                } else {
                    continue
                }

                // Lane restriction: only detect notes within the playfield lanes
                if (!isInLane(x, laneCenterX, laneWidth)) continue

                // Find bounding box
                val boundingBox = findNoteBoundingBox(pixels, width, height, x, y, sampleRate, color)

                if (boundingBox != null) {
                    val (left, top, right, bottom) = boundingBox
                    val noteWidth = right - left
                    val noteHeight = bottom - top

                    // Size filtering with resolution-aware bounds
                    val scaledMin = (minNoteSizePx * width / 1280.0).toInt().coerceAtLeast(4)
                    val scaledMax = (maxNoteSizePx * width / 1280.0).toInt().coerceAtMost(128)

                    if (noteWidth in scaledMin..scaledMax && noteHeight in scaledMin..scaledMax) {
                        val confidence = calculateConfidence(pixels, width, height, left, top, right, bottom, color)

                        if (confidence > minConfidenceThreshold) {
                            // Calculate normalized coordinates (0-1)
                            val normX = (left + noteWidth / 2).toFloat() / width
                            val normY = (top + noteHeight / 2).toFloat() / height

                            detectedNotes.add(DetectedNote(
                                id = generateNoteId(x, y, timestamp),
                                x = left + noteWidth / 2,
                                y = top + noteHeight / 2,
                                width = noteWidth,
                                height = noteHeight,
                                color = color,
                                confidence = confidence,
                                timestamp = timestamp,
                                noteType = noteType,
                                normalizedX = normX,
                                normalizedY = normY,
                                laneIndex = calculateLaneIndex(x, laneCenterX, laneWidth)
                            ))
                        }
                    }
                }
            }
        }

        // Apply temporal consistency filter
        val temporallyConsistent = filterTemporalConsistency(detectedNotes, timestamp)

        // Update recent detections
        updateRecentDetections(temporallyConsistent, timestamp)

        return temporallyConsistent
    }

    /**
     * Calculate appropriate sample rate based on resolution
     */
    private fun calculateSampleRate(width: Int, height: Int): Int {
        val totalPixels = width.toLong() * height
        return when {
            totalPixels > 1_920_000L -> 8  // 1080p+
            totalPixels > 921_600L -> 6    // 720p+
            else -> 4                      // Lower resolutions
        }
    }

    /**
     * Check if a point is within the playfield lanes
     */
    private fun isInLane(x: Int, laneCenterX: Int, laneWidth: Int): Boolean {
        val halfWidth = laneWidth / 2
        return x in (laneCenterX - halfWidth)..(laneCenterX + halfWidth)
    }

    /**
     * Calculate which lane a detection belongs to (0-indexed from left)
     */
    private fun calculateLaneIndex(x: Int, laneCenterX: Int, laneWidth: Int): Int {
        val laneCount = 4 // osu!mania standard
        val laneWidthPx = laneWidth / laneCount
        val relativeX = x - (laneCenterX - laneWidth / 2)
        return (relativeX / laneWidthPx).coerceIn(0, laneCount - 1)
    }

    /**
     * Find note bounding box by expanding from seed pixel.
     * Properly handles color similarity and aspect ratio.
     */
    private fun findNoteBoundingBox(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        sampleRate: Int,
        targetColor: Int
    ): IntArray? {
        var left = startX
        var right = startX
        var top = startY
        var bottom = startY

        // Expand left
        var x = startX
        while (x >= 0 && isSimilarColor(pixels[startY * width + x], targetColor)) {
            left = x
            x -= sampleRate
        }

        // Expand right
        x = startX
        while (x < width && isSimilarColor(pixels[startY * width + x], targetColor)) {
            right = x
            x += sampleRate
        }

        // Expand top
        var y = startY
        while (y >= 0 && isSimilarColor(pixels[y * width + startX], targetColor)) {
            top = y
            y -= sampleRate
        }

        // Expand bottom
        y = startY
        while (y < height && isSimilarColor(pixels[y * width + startX], targetColor)) {
            bottom = y
            y += sampleRate
        }

        val boxWidth = right - left + sampleRate
        val boxHeight = bottom - top + sampleRate

        // Verify roughly square (notes are circular)
        val aspectRatio = boxWidth.toFloat() / boxHeight
        if (aspectRatio < 0.6 || aspectRatio > 1.7) {
            return null
        }

        return intArrayOf(left, top, right, bottom)
    }

    fun isSimilarColor(color1: Int, color2: Int): Boolean {
        val r1 = Color.red(color1)
        val g1 = Color.green(color1)
        val b1 = Color.blue(color1)

        val r2 = Color.red(color2)
        val g2 = Color.green(color2)
        val b2 = Color.blue(color2)

        val diff = sqrt(
            Math.pow(r1 - r2, 2.0) +
            Math.pow(g1 - g2, 2.0) +
            Math.pow(b1 - b2, 2.0)
        )

        return diff < 40 // Slightly more tolerant for skin variation
    }

    // Test-friendly alias
    fun calculateColorSimilarity(color1: Int, color2: Int): Float {
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

    private fun calculateConfidence(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        targetColor: Int
    ): Float {
        val totalPixels = (right - left + 1) * (bottom - top + 1)
        var matchingPixels = 0

        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2

        for (y in top..bottom) {
            for (x in left..right) {
                val pixelIndex = y * width + x
                if (isSimilarColor(pixels[pixelIndex], targetColor)) {
                    matchingPixels++
                }
            }
        }

        return matchingPixels.toFloat() / totalPixels
    }

    private fun isNoteColor(hue: Float, ranges: List<ColorRange>): Boolean {
        return ranges.any { it.contains(hue) }
    }

    /**
     * Filter detections by temporal consistency.
     * Rejects detections that appear/disappear too quickly without movement context.
     */
    private fun filterTemporalConsistency(detections: List<DetectedNote>, timestamp: Long): List<DetectedNote> {
        val filtered = mutableListOf<DetectedNote>()
        val currentTimeMs = timestamp / 1_000_000

        for (detection in detections) {
            val detectionId = detection.id

            // Check if we've seen this note recently
            val recent = recentDetections[detectionId]
            if (recent != null) {
                // Note was seen before - check consistency
                val timeDiffMs = currentTimeMs - recent.timestamp / 1_000_000
                val distance = sqrt(
                    Math.pow(detection.x - recent.x, 2.0) +
                    Math.pow(detection.y - recent.y, 2.0)
                ).toFloat()

                // If moved too far for the time elapsed, reject (likely false positive)
                val maxExpectedDistance = 200.0f * (timeDiffMs / 16.0f) // ~200px per frame at 60fps
                if (distance <= maxExpectedDistance) {
                    filtered.add(detection)
                }
            } else {
                // New detection - accept but mark for tracking
                filtered.add(detection)
            }
        }

        return filtered
    }

    private fun updateRecentDetections(detections: List<DetectedNote>, timestamp: Long) {
        // Remove old detections
        val cutoffTime = timestamp - maxDetectionAgeMs * 1_000_000
        recentDetections.removeIf { _, it -> it.timestamp < cutoffTime }

        // Add new detections
        for (detection in detections) {
            recentDetections[detection.id] = detection
        }
    }

    private fun generateNoteId(x: Int, y: Int, timestamp: Long): String {
        return "note_${x}_${y}_${timestamp}"
    }

    /**
     * Manual fallback: create a detection at a specific position
     * Used when automatic detection fails and user manually places a note
     */
    fun createManualDetection(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        timestamp: Long,
        noteType: DetectedNote.NoteType,
        confidence: Float = 1.0f
    ): DetectedNote {
        return DetectedNote(
            id = "manual_${x}_${y}_${timestamp}",
            x = x,
            y = y,
            width = width,
            height = height,
            color = 0xFFFFFFFF,
            confidence = confidence,
            timestamp = timestamp,
            noteType = noteType,
            normalizedX = x.toFloat() / 1280f, // Will be recalculated with actual width
            normalizedY = y.toFloat() / 720f,
            laneIndex = -1
        )
    }

    /**
     * Get detection statistics for diagnostics
     */
    fun getDetectionStatistics(): DetectionStatistics {
        return DetectionStatistics(
            recentDetectionCount = recentDetections.size,
            minConfidenceThreshold = minConfidenceThreshold,
            gameMode = gameMode
        )
    }

    // ============ DATA CLASSES ============

    data class ColorRange(
        val min: Int,
        val max: Int,
        val label: String
    ) {
        fun contains(hue: Float): Boolean {
            val h = hue.toInt()
            return if (min <= max) {
                h in min..max
            } else {
                h >= min || h <= max // Wrap-around (e.g., red 340-20)
            }
        }
    }

    data class DetectionStatistics(
        val recentDetectionCount: Int,
        val minConfidenceThreshold: Float,
        val gameMode: GameMode
    )

    enum class GameMode {
        OSU_MANIA, // Vertical falling notes, lanes, chords, holds
        OSU_STANDARD // Cursor, circles, sliders, spinners (NOT SUPPORTED)
    }
}

/**
 * Extended DetectedNote with normalized coordinates and lane info.
 * These fields are added to the base class in FrameProcessor.
 */
object DetectedNoteExtensions {
    // These are conceptual extensions - actual fields are in FrameProcessor.DetectedNote
    const val MANUAL_DETECTION_CONFIDENCE = 1.0f
}
