package com.example.osuautoplayer

import com.example.osuautoplayer.processing.FrameProcessor.TrackedNote
import com.example.osuautoplayer.tracking.NoteTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NoteTrackerTest {

    private lateinit var tracker: NoteTracker

    @Before
    fun setUp() {
        tracker = NoteTracker()
    }

    @Test
    fun testNewTrackCreation() {
        val detection = com.example.osuautoplayer.processing.FrameProcessor.DetectedNote(
            id = "test_note_1",
            x = 100,
            y = 200,
            width = 16,
            height = 16,
            color = 0xFFFF0000, // Red
            confidence = 0.9f,
            timestamp = System.nanoTime(),
            com.example.osuautoplayer.processing.FrameProcessor.DetectedNote.NoteType.NORMAL
        )

        val tracks = tracker.updateTracks(listOf(detection), System.nanoTime())
        
        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("test_note_1", track.id)
        assertEquals(100, track.x)
        assertEquals(200, track.y)
        assertEquals(0xFFFF0000, track.color)
        assertEquals(0.9f, track.confidence, 0.001f)
        assertEquals(com.example.osuautoplayer.processing.FrameProcessor.TrackedNote.TrackingState.DETECTED, track.state)
    }

    @Test
    fun testTrackUpdateWithMovement() {
        val timestamp1 = System.nanoTime()
        val detection1 = com.example.osuautoplayer.processing.FrameProcessor.DetectedNote(
            id = "moving_note",
            x = 100,
            y = 200,
            width = 16,
            height = 16,
            color = 0xFFFF0000,
            confidence = 0.9f,
            timestamp = timestamp1,
            com.example.osuautoplayer.processing.FrameProcessor.DetectedNote.NoteType.NORMAL
        )

        // First detection
        tracker.updateTracks(listOf(detection1), timestamp1)
        
        // Second detection after movement
        val timestamp2 = timestamp1 + (100_000_000) // 100ms later
        val detection2 = com.example.osuautoplayer.processing.FrameProcessor.DetectedNote(
            id = "moving_note",
            x = 150, // Moved 50px right
            y = 250, // Moved 50px down
            width = 16,
            height = 16,
            color = 0xFFFF0000,
            confidence = 0.8f,
            timestamp = timestamp2,
            com.example.osuautoplayer.processing.FrameProcessor.DetectedNote.NoteType.NORMAL
        )

        val tracks = tracker.updateTracks(listOf(detection2), timestamp2)
        
        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("moving_note", track.id)
        // Position should be updated to latest detection
        assertEquals(150, track.x)
        assertEquals(250, track.y)
        // Should have velocity
        assertTrue(track.velocityX > 0) // Moving right
        assertTrue(track.velocityY > 0) // Moving down
        // Should be tracking state
        assertEquals(com.example.osuautoplayer.processing.FrameProcessor.TrackedNote.TrackingState.TRACKING, track.state)
    }
}
