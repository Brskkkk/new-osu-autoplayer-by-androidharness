package com.example.osuautoplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.osuautoplayer.processing.FrameProcessor.TrackedNote
import com.example.osuautoplayer.settings.PreferencesManager

class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val preferences = PreferencesManager.getInstance()
    private val trackPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    
    private val idPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
        setShadowLayer(2f, 2f, 2f, Color.BLACK.toInt())
    }
    
    private val judgementLinePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    
    private var trackedNotes: List<TrackedNote> = emptyList()
    private var judgementLineX: Int = 0
    private var judgementLineY: Int = 0
    private var judgementLineWidth: Int = 0
    private var isCalibrated: Boolean = false
    
    fun setTrackingData(notes: List<TrackedNote>) {
        trackedNotes = notes
        invalidate()
    }
    
    fun setJudgementLine(centerX: Int, centerY: Int, width: Int) {
        judgementLineX = centerX
        judgementLineY = centerY
        judgementLineWidth = width
        invalidate()
    }
    
    fun setCalibrationStatus(isCalibrated: Boolean) {
        this.isCalibrated = isCalibrated
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!preferences.debugOverlayEnabled) return
        
        // Draw tracked notes
        for (note in trackedNotes) {
            val left = note.x - note.width / 2
            val top = note.y - note.height / 2
            val right = note.x + note.width / 2
            val bottom = note.y + note.height / 2
            
            // Color based on confidence
            trackPaint.color = when {
                note.confidence > 0.8f -> Color.GREEN
                note.confidence > 0.5f -> Color.YELLOW
                else -> Color.RED
            }
            
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), trackPaint)
            
            // Draw ID and confidence
            val label = "${note.id.substring(0, min(4, note.id.length))}: ${(note.confidence * 100).toInt()}%"
            canvas.drawText(label, left.toFloat(), (top - 5).toFloat(), idPaint)
            
            // Draw velocity vector if significant
            if (abs(note.velocityX) > 10f || abs(note.velocityY) > 10f) {
                val endX = note.x + (note.velocityX * 0.1f).toInt()
                val endY = note.y + (note.velocityY * 0.1f).toInt()
                canvas.drawLine(note.x.toFloat(), note.y.toFloat(), endX.toFloat(), endY.toFloat(), trackPaint)
            }
        }
        
        // Draw judgement line
        if (isCalibrated && judgementLineWidth > 0) {
            judgementLinePaint.color = if (isCalibrated) Color.RED else Color.GRAY
            val startX = judgementLineX - judgementLineWidth / 2
            val endX = judgementLineX + judgementLineWidth / 2
            canvas.drawLine(
                startX.toFloat(), judgementLineY.toFloat(),
                endX.toFloat(), judgementLineY.toFloat(),
                judgementLinePaint
            )
            
            // Draw calibration status
            val statusText = if (isCalibrated) "CALIBRATED" else "NOT CALIBRATED"
            canvas.drawText(statusText, 20f, 40f, idPaint)
        }
    }
}
