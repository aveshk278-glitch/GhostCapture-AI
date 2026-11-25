package com.ghostcapture.app

import com.ghostcapture.app.data.HudMask
import org.junit.Assert.assertEquals
import org.junit.Test

class HudMaskTest {

    @Test
    fun normalized_clampsOutOfBoundsRect() {
        val mask = HudMask("joystick", -0.1f, 0.9f, 0.5f, 0.5f).normalized()
        assertEquals(0f, mask.x, 0.001f)
        assertEquals(0.9f, mask.y, 0.001f)
        assertEquals(0.5f, mask.w, 0.001f)
        assertEquals(0.1f, mask.h, 0.001f)
    }

    @Test
    fun normalized_keepsValidRect() {
        val mask = HudMask("minimap", 0.8f, 0.02f, 0.15f, 0.12f).normalized()
        assertEquals(0.8f, mask.x, 0.001f)
        assertEquals(0.02f, mask.y, 0.001f)
        assertEquals(0.15f, mask.w, 0.001f)
        assertEquals(0.12f, mask.h, 0.001f)
    }

    @Test
    fun normalized_clampsWidthToFrameEdge() {
        val mask = HudMask("fire_button", 0.9f, 0.7f, 0.3f, 0.2f).normalized()
        assertEquals(0.1f, mask.w, 0.001f)
    }
}
