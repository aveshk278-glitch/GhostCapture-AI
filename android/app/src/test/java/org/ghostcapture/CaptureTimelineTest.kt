package org.ghostcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTimelineTest {
    @Test fun removesMultiplePauseIntervalsFromBothTracks() {
        val timeline = CaptureTimeline(100)
        assertNull(timeline.timestamp(99))
        assertEquals(0L, timeline.timestamp(100))
        timeline.setPaused(true, 150)
        assertNull(timeline.timestamp(150))
        timeline.setPaused(false, 450)
        assertEquals(50L, timeline.timestamp(450))
        timeline.setPaused(true, 500)
        timeline.setPaused(false, 700)
        assertNull(timeline.timestamp(499 + 1))
        assertEquals(150L, timeline.timestamp(750))
        assertEquals(25L, timeline.timestamp(125))
    }

    @Test fun repeatedControlsKeepTheOriginalPauseBoundary() {
        val timeline = CaptureTimeline(0)
        timeline.setPaused(true, 10)
        timeline.setPaused(true, 20)
        timeline.setPaused(false, 30)
        timeline.setPaused(false, 40)
        assertNull(timeline.timestamp(15))
        assertEquals(30L, timeline.timestamp(50))
    }
}
