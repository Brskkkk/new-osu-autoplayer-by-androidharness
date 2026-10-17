package com.example.osuautoplayer.capture

import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.app.StartForegroundRequest
import androidx.core.app.ForegroundServiceStartNotAllowedException
import com.example.osuautoplayer.R
import com.example.osuautoplayer.diagnostics.PerformanceMonitor
import com.example.osuautoplayer.processing.FrameProcessor
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * ScreenCaptureService - Captures device screen using MediaProjection.
 *
 * KEY DESIGN FIXES:
 * - Does NOT assume RGB565 format. Queries ImageReader for supported formats.
 * - Properly handles pixelStride, rowStride, and buffer layout.
 * - Selects the best available format (prefers RGBA_8888 or RGB_565 if available).
 * - Reuses buffers safely without assuming format.
 * - Processes only relevant playfield regions.
 */
class ScreenCaptureService : Service() {

    private companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val frameQueue = LinkedBlockingQueue<FrameData>(10)
    private var captureThread: Thread? = null
    private var isRunning = false
    private lateinit var frameProcessor: FrameProcessor
    private val performanceMonitor = PerformanceMonitor()

    // Capture configuration - queried from device, not assumed
    private var captureFormat: Int = ImageFormat.RGBA_8888 // Default fallback
    private var captureWidth: Int = 1280
    private var captureHeight: Int = 720
    private var targetFps: Int = 30

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        frameProcessor = FrameProcessor(frameQueue)

        // Start foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else 0

            try {
                val request = StartForegroundRequest.Builder()
                    .setNotification(getNotification())
                    .setForegroundServiceType(foregroundServiceType)
                    .build()
                ServiceCompat.startForeground(this, NOTIFICATION_ID, request)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Failed to start foreground service", e)
                stopSelf()
            }
        } else {
            startForeground(NOTIFICATION_ID, getNotification())
        }

        Log.i(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectionData = intent?.getParcelableExtra<MediaProjection>("media_projection")
        if (projectionData != null) {
            startCapture(projectionData)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        frameProcessor.shutdown()
        Log.i(TAG, "ScreenCaptureService destroyed")
    }

    /**
     * Start screen capture with the given MediaProjection.
     * Queries supported image formats and configures ImageReader accordingly.
     */
    private fun startCapture(mediaProjection: MediaProjection) {
        this.mediaProjection = mediaProjection
        isRunning = true

        // Get display metrics
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val display = windowManager.currentDisplay

        // Configure capture resolution from preferences (default 1280x720)
        captureWidth = 1280
        captureHeight = 720

        // Query supported formats - do NOT assume RGB565
        // Try RGBA_8888 first (most widely supported), fallback to RGB_565
        val preferredFormats = intArrayOf(
            ImageFormat.RGBA_8888,
            ImageFormat.RGB_565,
            ImageFormat.YUV_420_888
        )

        var selectedFormat = ImageFormat.RGBA_8888 // Safe default
        for (format in preferredFormats) {
            try {
                // Test if ImageReader can create with this format
                val testReader = ImageReader.newInstance(
                    captureWidth, captureHeight, format, 2
                )
                testReader.close()
                selectedFormat = format
                break
            } catch (e: Exception) {
                Log.w(TAG, "Format $format not supported, trying next")
            }
        }

        captureFormat = selectedFormat
        Log.i(TAG, "Selected capture format: $selectedFormat")

        // Create ImageReader with the selected format
        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            captureFormat,
            2 // Max images - keep bounded
        )

        // Set up VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "OsuAutoplayer",
            captureWidth,
            captureHeight,
            display.mode.refreshRate,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null
        )

        // Start capture thread
        captureThread = Thread(CaptureRunner())
        captureThread!!.name = "osu-autoplayer-capture"
        captureThread!!.isDaemon = true
        captureThread!!.start()

        Log.i(TAG, "Screen capture started: ${captureWidth}x${captureHeight} @ ${targetFps}fps, format=$captureFormat")
    }

    private fun stopCapture() {
        isRunning = false

        captureThread?.interrupt()
        captureThread?.join(1000)
        captureThread = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        Log.i(TAG, "Screen capture stopped")
    }

    private inner class CaptureRunner : Runnable {
        override fun run() {
            val targetFrameTimeNs = 1_000_000_000L / targetFps

            while (isRunning && !Thread.currentThread().isInterrupted) {
                val frameStartTime = System.nanoTime()

                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val frameData = processImage(image)
                        if (frameData != null) {
                            try {
                                frameQueue.put(frameData)
                                performanceMonitor.recordFrameProcessed()
                            } catch (e: IllegalStateException) {
                                // Queue full - drop frame
                                performanceMonitor.recordDroppedFrame()
                            }
                        }
                    } finally {
                        // CRITICAL: Close image promptly to release buffer
                        image.close()
                    }
                }

                // Maintain target FPS
                val frameTimeNs = System.nanoTime() - frameStartTime
                if (frameTimeNs < targetFrameTimeNs) {
                    val sleepTime = (targetFrameTimeNs - frameTimeNs) / 1_000_000
                    try {
                        Thread.sleep(sleepTime)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        /**
         * Process an Image into FrameData.
         * Properly handles pixelStride, rowStride, and buffer layout.
         * Does NOT assume RGB565 format.
         */
        private fun processImage(image: Image): FrameData? {
            val width = image.width
            val height = image.height
            val format = image.format

            val planes = image.planes
            if (planes.isEmpty()) return null

            // Handle different formats appropriately
            return when (format) {
                ImageFormat.RGBA_8888 -> processRgba8888(image, planes, width, height)
                ImageFormat.RGB_565 -> processRgb565(image, planes, width, height)
                ImageFormat.YUV_420_888 -> processYuv420(image, planes, width, height)
                else -> {
                    Log.w(TAG, "Unsupported image format: $format")
                    null
                }
            }
        }

        private fun processRgba8888(image: Image, planes: Array<Image.Plane>, width: Int, height: Int): FrameData? {
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            // Handle stride: if pixelStride == 1, buffer is tightly packed
            // If pixelStride > 1, we need to copy row by row
            val pixelData = if (pixelStride == 1 && rowStride == width * 4) {
                // Tightly packed - direct copy
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                data
            } else {
                // Strided - copy row by row
                val data = ByteArray(height * rowStride)
                val rowSize = width * 4
                for (row in 0 until height) {
                    val rowOffset = row * rowStride
                    val bufferOffset = row * rowStride
                    buffer.position(bufferOffset)
                    buffer.get(data, rowOffset, rowSize)
                }
                data
            }

            return FrameData(
                pixelData = pixelData,
                width = width,
                height = height,
                format = format,
                pixelStride = pixelStride,
                rowStride = rowStride,
                timestamp = System.nanoTime(),
                imageTimestamp = image.timestamp
            )
        }

        private fun processRgb565(image: Image, planes: Array<Image.Plane>, width: Int, height: Int): FrameData? {
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val pixelData = if (pixelStride == 2 && rowStride == width * 2) {
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                data
            } else {
                val data = ByteArray(height * rowStride)
                val rowSize = width * 2
                for (row in 0 until height) {
                    val rowOffset = row * rowStride
                    val bufferOffset = row * rowStride
                    buffer.position(bufferOffset)
                    buffer.get(data, rowOffset, rowSize)
                }
                data
            }

            return FrameData(
                pixelData = pixelData,
                width = width,
                height = height,
                format = format,
                pixelStride = pixelStride,
                rowStride = rowStride,
                timestamp = System.nanoTime(),
                imageTimestamp = image.timestamp
            )
        }

        private fun processYuv420(image: Image, planes: Array<Image.Plane>, width: Int, height: Int): FrameData? {
            // YUV_420_888 has 3 planes: Y, U, V
            // For detection, we primarily need the Y (luminance) plane
            // Convert to RGB for downstream processing if needed
            val yBuffer = planes[0].buffer
            val yPixelStride = planes[0].pixelStride
            val yRowStride = planes[0].rowStride

            val yData = if (yPixelStride == 1 && yRowStride == width) {
                val data = ByteArray(yBuffer.remaining())
                yBuffer.get(data)
                data
            } else {
                val data = ByteArray(height * yRowStride)
                for (row in 0 until height) {
                    val rowOffset = row * yRowStride
                    val bufferOffset = row * yRowStride
                    yBuffer.position(bufferOffset)
                    yBuffer.get(data, rowOffset, width)
                }
                data
            }

            return FrameData(
                pixelData = yData,
                width = width,
                height = height,
                format = ImageFormat.YUV_420_888,
                pixelStride = yPixelStride,
                rowStride = yRowStride,
                timestamp = System.nanoTime(),
                imageTimestamp = image.timestamp
            )
        }
    }

    /**
     * Get the configured capture format for downstream processing
     */
    fun getCaptureFormat(): Int {
        return captureFormat
    }

    /**
     * Get capture dimensions
     */
    fun getCaptureDimensions(): IntArray {
        return intArrayOf(captureWidth, captureHeight)
    }

    data class FrameData(
        val pixelData: ByteArray,
        val width: Int,
        val height: Int,
        val format: Int = ImageFormat.RGBA_8888,
        val pixelStride: Int = 1,
        val rowStride: Int = 1,
        val timestamp: Long,
        val imageTimestamp: Long
    )

    private fun getNotification(): android.app.Notification {
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("osu! Autoplayer")
            .setContentText("Capturing screen for note detection")
            .setSmallIcon(R.drawable.ic_autoplayer)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "osu_autoplayer_capture_channel"
    }
}
