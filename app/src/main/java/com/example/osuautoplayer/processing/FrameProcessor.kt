package com.example.osuautoplayer.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.osuautoplayer.capture.ScreenCaptureService.FrameData
import com.example.osuautoplayer.detection.NoteDetector
import com.example.osuautoplayer.tracking.NoteTracker
import com.example.osuautoplayer.input.InputScheduler
import com.example.osuautoplayer.calibration.JudgementLineCalibrator
import com.example.osuautoplayer.diagnostics.PerformanceMonitor
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FrameProcessor - Main frame processing pipeline.
 *
 * Orchestrates capture, detection, tracking, calibration, and input scheduling
 * with proper separation of concerns and thread safety.
 */
class FrameProcessor(
    private val frameQueue: LinkedBlockingQueue<ScreenCaptureService.FrameData>,
    private val performanceMonitor: PerformanceMonitor
) {

    private val noteDetector = NoteDetector()
    private val noteTracker = NoteTracker()
    private val inputScheduler = InputScheduler(null, noteTracker)
    private val judgementLineCalibrator = JudgementLineCalibrator()
    private val performanceOptimizer = com.example.osuautoplayer.processing.PerformanceOptimizer()

    private val processingThread: Thread
    private val running = AtomicBoolean(true)

    // Latest tracking data for UI access
    private var latestTrackedNotes: List<TrackedNote> = emptyList()
    private var latestDetectionCount: Int = 0

    init {
        processingThread = Thread(ProcessingRunner())
        processingThread.isDaemon = true
        processingThread.name = "osu-autoplayer-frame-processor"
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
        performanceOptimizer.shutdown()
        inputScheduler.shutdown()
    }

    private inner class ProcessingRunner : Runnable {
        override fun run() {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val frameStartTime = System.nanoTime()

                try {
                    // Acquire the newest available frame (drop stale ones)
                    val frameData = acquireNewestFrame()
                    if (frameData == null) continue

                    val frameAgeNs = System.nanoTime() - frameData.timestamp

                    // Check if we should process this frame
                    if (!performanceOptimizer.shouldProcessFrame(frameAgeNs, frameQueue.size())) {
                        performanceMonitor.recordDroppedFrame()
                        continue
                    }

                    processFrame(frameData)

                    val frameTimeNs = System.nanoTime() - frameStartTime
                    performanceMonitor.recordFrameProcessingTime(frameTimeNs)
                    performanceOptimizer.recordFrameProcessed(frameTimeNs)

                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    performanceMonitor.recordError(e)
                }
            }
        }

        /**
         * Acquire the newest frame from the queue, discarding older ones.
         * This ensures we process the most recent frame, not stale ones.
         */
        private fun acquireNewestFrame(): FrameData? {
            var newest: FrameData? = null
            while (true) {
                val frame = frameQueue.poll()
                if (frame == null) break
                newest = frame
            }
            return newest
        }

        private fun processFrame(frameData: ScreenCaptureService.FrameData) {
            performanceMonitor.recordFrameCaptured()

            // Convert byte array to bitmap using proper format handling
            val bitmap = convertToBitmap(frameData)

            try {
                // Detect notes in the frame
                val detectedNotes = noteDetector.detectNotes(bitmap, frameData.timestamp)
                latestDetectionCount = detectedNotes.size

                // Update tracking with new detections
                val trackedNotes = noteTracker.updateTracks(detectedNotes, frameData.timestamp)
                latestTrackedNotes = trackedNotes

                // Schedule inputs based on tracked notes
                inputScheduler.scheduleInputs(trackedNotes, frameData.timestamp)

                // Update judgement line calibration if needed
                judgementLineCalibrator.updateCalibration(bitmap)

                // Update input scheduler latencies
                inputScheduler.updateLatencies(
                    0, // capture latency - would be measured separately
                    frameTimeNs(),
                    5_000_000 // ~5ms dispatch latency estimate
                )
            } finally {
                bitmap.recycle()
            }
        }

        private var lastFrameTimeNs: Long = 0

        private fun frameTimeNs(): Long {
            val now = System.nanoTime()
            val result = now - lastFrameTimeNs
            lastFrameTimeNs = now
            return result
        }

        /**
         * Convert FrameData byte array to Bitmap.
         * Handles different pixel formats and strides properly.
         */
        private fun convertToBitmap(frameData: ScreenCaptureService.FrameData): Bitmap {
            val config = when (frameData.format) {
                android.graphics.ImageFormat.RGB_565 -> Bitmap.Config.RGB_565
                else -> Bitmap.Config.ARGB_8888
            }

            val bitmap = Bitmap.createBitmap(frameData.width, frameData.height, config)

            // Handle stride: if rowStride != width * bytesPerPixel, copy row by row
            val bytesPerPixel = if (frameData.format == android.graphics.ImageFormat.RGB_565) 2 else 4
            val expectedRowSize = frameData.width * bytesPerPixel

            if (frameData.rowStride == expectedRowSize && frameData.pixelStride == bytesPerPixel) {
                // Tightly packed - direct copy
                val buffer = ByteBuffer.wrap(frameData.pixelData)
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Strided - copy row by row
                for (row in 0 until frameData.height) {
                    val srcOffset = row * frameData.rowStride
                    val srcEnd = srcOffset + expectedRowSize
                    val rowData = frameData.pixelData.copyOfRange(srcOffset, srcEnd)
                    val rowBuffer = ByteBuffer.wrap(rowData)
                    // Set the row in bitmap
                    val pixels = IntArray(frameData.width)
                    for (i in pixels.indices) {
                        if (frameData.format == android.graphics.ImageFormat.RGB_565) {
                            val offset = i * 2
                            val b = rowData.offsetToLong(offset)
                            val g = rowData.offsetToLong(offset + 1)
                            pixels[i] = (b shl 8) or (g and 0xFF) // Simplified RGB565 to ARGB
                        } else {
                            val offset = i * 4
                            pixels[i] = (rowData[offset + 3] shl 24) or
                                (rowData[offset + 2] shl 16) or
                                (rowData[offset + 1] shl 8) or
                                rowData[offset]
                        }
                    }
                    bitmap.setPixels(pixels, 0, frameData.width, 0, row, frameData.width, 1)
                }
            }

            return bitmap
        }
    }

    // ============ Public API ============

    fun getLatestTrackedNotes(): List<TrackedNote> {
        return latestTrackedNotes
    }

    fun getNoteTracker(): NoteTracker {
        return noteTracker
    }

    fun getInputScheduler(): InputScheduler {
        return inputScheduler
    }

    fun getJudgementLineCalibrator(): JudgementLineCalibrator {
        return judgementLineCalibrator
    }

    fun getDetectionCount(): Int {
        return latestDetectionCount
    }

    // ============ DATA CLASSES ============

    data class DetectedNote(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val color: Int,
        val confidence: Float,
        val timestamp: Long,
        val noteType: NoteType,
        val normalizedX: Float = 0.0f,
        val normalizedY: Float = 0.0f,
        val laneIndex: Int = -1
    ) {
        enum class NoteType { NORMAL, HOLD_HEAD, HOLD_TAIL }
    }

    data class TrackedNote(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val color: Int,
        val confidence: Float,
        val velocityX: Float,
        val velocityY: Float,
        val estimatedArrivalTime: Long?,
        val normalizedX: Float = 0.0f,
        val normalizedY: Float = 0.0f,
        val laneIndex: Int = -1,
        val noteType: DetectedNote.NoteType,
        val state: TrackingState
    ) {
        enum class TrackingState {
            DETECTED, TRACKING, SCHEDULED, PRESSED, COMPLETED, MISSED, EXPIRED
        }
    }
}

// Helper extension for byte reading
private fun ByteArray.offsetToLong(offset: Int): Long {
    return this[offset].toInt() and 0xFF
}
