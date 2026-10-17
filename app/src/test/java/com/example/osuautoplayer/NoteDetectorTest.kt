package com.example.osuautoplayer

import android.graphics.Bitmap
import android.graphics.Color
import com.example.osuautoplayer.detection.NoteDetector
import com.example.osuautoplayer.processing.FrameProcessor.DetectedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDetectorTest {

    @Test
    fun testNoteDetection() {
        val detector = NoteDetector()
        
        // Create a simple test bitmap with a colored circle
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Draw a red circle in the center
        val paint = android.graphics.Paint().apply {
            color = Color.RED
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(32f, 32f, 16f, paint)
        
        // Detect notes
        val notes = detector.detectNotes(bitmap, System.nanoTime())
        
        // Verify we detected something
        assertNotNull(notes)
        assertTrue("Should detect at least one note", notes.isNotEmpty())
        
        // Verify the detected note properties
        val note = notes[0]
        assertEquals("Should be a normal note", DetectedNote.NoteType.NORMAL, note.noteType)
        assertTrue("Confidence should be reasonable", note.confidence > 0.3f)
        assertEquals("X position should be around center", 32, note.x, 5)
        assertEquals("Y position should be around center", 32, note.y, 5)
    }
    
    @Test
    fun testColorSimilarity() {
        val detector = NoteDetector()
        
        // Test identical colors
        val similarity1 = detector.calculateColorSimilarity(Color.RED, Color.RED)
        assertEquals(1.0f, similarity1, 0.001f)
        
        // Test similar colors
        val similarity2 = detector.calculateColorSimilarity(Color.RED, 0xFFFF8080) // Light red
        assertTrue(similarity2 > 0.8f)
        
        // Test different colors
        val similarity3 = detector.calculateColorSimilarity(Color.RED, Color.BLUE)
        assertTrue(similarity3 < 0.3f)
    }
}
