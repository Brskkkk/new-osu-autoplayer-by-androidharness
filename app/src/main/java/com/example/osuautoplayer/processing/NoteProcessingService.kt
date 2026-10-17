package com.example.osuautoplayer.processing

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.app.StartForegroundRequest
import com.example.osuautoplayer.R
import com.example.osuautoplayer.capture.ScreenCaptureService
import com.example.osuautoplayer.diagnostics.PerformanceMonitor

class NoteProcessingService : Service() {

    private companion object {
        const val TAG = "NoteProcessingService"
        const val NOTIFICATION_ID = 2
    }

    private lateinit var frameProcessor: FrameProcessor
    private val performanceMonitor = PerformanceMonitor()
    private var isBound = false

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): NoteProcessingService = this@NoteProcessingService
    }

    override fun onCreate() {
        super.onCreate()
        
        // Start foreground service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val request = StartForegroundRequest.Builder()
                    .setNotification(getNotification())
                    .build()
                ServiceCompat.startForeground(this, NOTIFICATION_ID, request)
            } catch (e: androidx.core.app.ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Failed to start foreground service", e)
                stopSelf()
            }
        } else {
            startForeground(NOTIFICATION_ID, getNotification())
        }
        
        // Note: In a real implementation, we'd get the frame queue from ScreenCaptureService
        // For now, we'll create a local one
        val frameQueue = new java.util.concurrent.LinkedBlockingQueue<ScreenCaptureService.FrameData>(10)
        frameProcessor = FrameProcessor(frameQueue)
        
        Log.i(TAG, "NoteProcessingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        isBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        frameProcessor.shutdown()
        Log.i(TAG, "NoteProcessingService destroyed")
    }

    private fun getNotification(): android.app.Notification {
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("osu! Autoplayer Processing")
            .setContentText("Analyzing gameplay and scheduling inputs")
            .setSmallIcon(R.drawable.ic_autoplayer)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "osu_autoplayer_processing_channel"
    }
}
