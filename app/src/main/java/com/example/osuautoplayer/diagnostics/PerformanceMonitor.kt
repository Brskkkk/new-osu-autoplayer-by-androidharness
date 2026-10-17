package com.example.osuautoplayer.diagnostics

import android.util.Log
import com.example.osuautoplayer.processing.FrameProcessor.ScheduledInput

class PerformanceMonitor {

    private var framesCaptured = 0L
    private var framesProcessed = 0L
    private var totalProcessingTimeNs = 0L
    private var maxProcessingTimeNs = 0L
    private var droppedFrames = 0L
    private var successfulInputs = 0L
    private var failedInputs = 0L
    private var lateInputs = 0L
    
    private val frameTimes = java.util.concurrent.LinkedBlockingQueue<Long>(100)
    private val inputLatencies = java.util.concurrent.LinkedBlockingQueue<Long>(100)
    
    fun recordFrameCaptured() {
        framesCaptured++
    }
    
    fun recordFrameProcessed() {
        framesProcessed++
    }
    
    fun recordFrameProcessingTime(timeNs: Long) {
        totalProcessingTimeNs += timeNs
        if (timeNs > maxProcessingTimeNs) maxProcessingTimeNs = timeNs
        frameTimes.offer(timeNs)
        if (frameTimes.size() > 100) frameTimes.poll()
    }
    
    fun recordDroppedFrame() {
        droppedFrames++
    }
    
    fun recordSuccessfulInput(input: ScheduledInput, timestamp: Long) {
        successfulInputs++
        val latency = timestamp - input.estimatedArrivalTime
        inputLatencies.offer(latency)
        if (inputLatencies.size() > 100) inputLatencies.poll()
    }
    
    fun recordFailedInput(input: ScheduledInput, error: Exception) {
        failedInputs++
        Log.w("PerformanceMonitor", "Failed input: ${input.id}", error)
    }
    
    fun recordLateInput(input: ScheduledInput, currentTime: Long) {
        lateInputs++
        Log.w("PerformanceMonitor", "Late input: ${input.id} by ${(currentTime - input.estimatedArrivalTime) / 1_000_000}ms")
    }
    
    fun recordError(error: Exception) {
        Log.e("PerformanceMonitor", "Processing error", error)
    }
    
    fun getAverageFrameProcessingTimeMs(): Float {
        if (frameTimes.isEmpty()) return 0f
        val sum = frameTimes.sum()
        return (sum / frameTimes.size()).toFloat() / 1_000_000
    }
    
    fun getMaxFrameProcessingTimeMs(): Float {
        return maxProcessingTimeNs.toFloat() / 1_000_000
    }
    
    fun getCaptureFps(): Float {
        // Simplified - in practice would use timestamps
        return if (framesProcessed > 0) 30.0f else 0.0f
    }
    
    fun getInputSuccessRate(): Float {
        val totalAttempts = successfulInputs + failedInputs
        return if (totalAttempts > 0) (successfulInputs.toFloat() / totalAttempts) * 100 else 0.0f
    }
    
    fun getAverageInputLatencyMs(): Float {
        if (inputLatencies.isEmpty()) return 0f
        val sum = inputLatencies.sum()
        return (sum / inputLatencies.size()).toFloat() / 1_000_000
    }
    
    fun getStatsString(): String {
        return String.format(
            "Capture: %d frames\nProcessed: %d frames\nAvg Proc Time: %.2fms\nMax Proc Time: %.2fms\nDropped: %d\n" +
            "Successful Inputs: %d\nFailed Inputs: %d\nLate Inputs: %d\nInput Success Rate: %.1f%%\n" +
            "Avg Input Latency: %.2fms",
            framesCaptured, framesProcessed,
            getAverageFrameProcessingTimeMs(),
            getMaxFrameProcessingTimeMs(),
            droppedFrames,
            successfulInputs, failedInputs, lateInputs,
            getInputSuccessRate(),
            getAverageInputLatencyMs()
        )
    }
    
    fun reset() {
        framesCaptured = 0
        framesProcessed = 0
        totalProcessingTimeNs = 0
        maxProcessingTimeNs = 0
        droppedFrames = 0
        successfulInputs = 0
        failedInputs = 0
        lateInputs = 0
        frameTimes.clear()
        inputLatencies.clear()
    }
}
