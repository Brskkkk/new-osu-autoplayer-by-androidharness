package com.example.osuautoplayer.calibration

import android.graphics.Bitmap
import android.graphics.Point
import com.example.osuautoplayer.settings.PreferencesManager
import kotlin.math.abs

class JudgementLineCalibrator {

    private val preferences = PreferencesManager.getInstance()
    
    // Calibration data
    private var judgementLine: JudgementLine? = null
    private var isCalibrated = false
    private var calibrationConfidence = 0.0f
    
    fun updateCalibration(bitmap: Bitmap) {
        if (!preferences.autoCalibrationEnabled) return
        
        val detectedLine = detectJudgementLine(bitmap)
        if (detectedLine != null && detectedLine.confidence > 0.5f) {
            judgementLine = detectedLine
            isCalibrated = true
            calibrationConfidence = detectedLine.confidence
        }
    }
    
    fun getJudgementLine(): JudgementLine? {
        return judgementLine
    }
    
    fun isCalibrated(): Boolean {
        return isCalibrated
    }
    
    fun getCalibrationConfidence(): Float {
        return calibrationConfidence
    }
    
    fun manualCalibrate(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int,
        angle: Float
    ) {
        judgementLine = JudgementLine(
            centerX = centerX,
            centerY = centerY,
            width = width,
            height = height,
            angle = angle,
            confidence = 1.0f
        )
        isCalibrated = true
        calibrationConfidence = 1.0f
    }
    
    private fun detectJudgementLine(bitmap: Bitmap): JudgementLine? {
        val width = bitmap.width
        val height = bitmap.height
        
        // Convert bitmap to grayscale for edge detection
        val grayscale = IntArray(width * height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Convert to grayscale
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = color shr 16 and 0xFF
            val g = color shr 8 and 0xFF
            val b = color and 0xFF
            grayscale[i] = ((r * 299 + g * 587 + b * 114) / 1000).toInt()
        }
        
        // Apply horizontal edge detection (Sobel operator)
        val horizontalEdges = IntArray(width * height)
        val verticalEdges = IntArray(width * height)
        
        // Sobel kernels
        val kx = intArrayOf(-1, 0, 1, -2, 0, 2, -1, 0, 1)
        val ky = intArrayOf(-1, -2, -1, 0, 0, 0, 1, 2, 1)
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var gx = 0
                var gy = 0
                
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixelIndex = (y + ky) * width + (x + kx)
                        val pixel = grayscale[pixelIndex]
                        gx += pixel * kx[(ky + 1) * 3 + (kx + 1)]
                        gy += pixel * ky[(ky + 1) * 3 + (kx + 1)]
                    }
                }
                
                val index = y * width + x
                horizontalEdges[index] = gx
                verticalEdges[index] = gy
            }
        }
        
        // Find strong horizontal lines (potential judgement lines)
        val lineCandidates = mutableListOf<LineCandidate>()
        
        for (y in height/4 until 3*height/4) {  // Focus on middle half of screen
            var lineStrength = 0
            var lineStart = -1
            
            for (x in 0 until width - 1) {
                val index = y * width + x
                val edgeStrength = abs(horizontalEdges[index])
                
                if (edgeStrength > 30) {  // Edge threshold
                    if (lineStart == -1) lineStart = x
                    lineStrength += edgeStrength
                } else if (lineStart != -1) {
                    val lineWidth = x - lineStart
                    if (lineWidth > 20) {  // Minimum line width
                        val avgStrength = lineStrength / lineWidth
                        lineCandidates.add(LineCandidate(
                            y = y,
                            startX = lineStart,
                            endX = x - 1,
                            strength = avgStrength,
                            width = lineWidth
                        ))
                    }
                    lineStrength = 0
                    lineStart = -1
                }
            }
            
            // Handle line that extends to edge
            if (lineStart != -1) {
                val lineWidth = width - lineStart
                if (lineWidth > 20) {
                    val avgStrength = lineStrength / lineWidth
                    lineCandidates.add(LineCandidate(
                        y = y,
                        startX = lineStart,
                        endX = width - 1,
                        strength = avgStrength,
                        width = lineWidth
                    ))
                }
            }
        }
        
        // Find the best candidate
        if (lineCandidates.isEmpty()) return null
        
        val bestCandidate = lineCandidates.maxByOrNull { it.strength }!!
        
        // Calculate confidence based on strength and consistency
        val maxPossibleStrength = 255.0f
        val confidence = minOf(bestCandidate.strength / maxPossibleStrength, 1.0f)
        
        return JudgementLine(
            centerX = (bestCandidate.startX + bestCandidate.endX) / 2,
            centerY = bestCandidate.y,
            width = bestCandidate.width,
            height = 4,  // Default judgement line height
            angle = 0.0f,  // Assume horizontal for now
            confidence = confidence
        )
    }

    private data class LineCandidate(
        val y: Int,
        val startX: Int,
        val endX: Int,
        val strength: Float,
        val width: Int
    )
    
    data class JudgementLine(
        val centerX: Int,
        val centerY: Int,
        val width: Int,
        val height: Int,
        val angle: Float,
        val confidence: Float
    )
}
