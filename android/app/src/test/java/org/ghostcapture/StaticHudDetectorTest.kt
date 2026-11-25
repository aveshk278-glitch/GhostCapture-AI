package org.ghostcapture

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StaticHudDetectorTest {
    @Test fun findsPersistentOverlayAgainstMotion() {
        val width = 120
        val height = 120
        val random = Random(19)
        val frames = List(8) {
            IntArray(width * height) { index ->
                val y = index / width
                val x = index % width
                if (x in 10..22 && y in 10..22) 255 else random.nextInt(0, 145)
            }
        }
        val result = StaticHudDetector.suggest(frames, width, height)
        val middle = 16f / 120
        assertTrue(result.any { middle in it.left..it.right && middle in it.top..it.bottom })
    }

    @Test fun staticBackgroundDoesNotProduceSuggestions() {
        val frames = List(8) { IntArray(64 * 64) { if (it % 64 < 32) 0 else 255 } }
        assertTrue(StaticHudDetector.suggest(frames, 64, 64).isEmpty())
    }
}
