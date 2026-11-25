package org.ghostcapture

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** Proposes rectangles around sharp, persistent edges beside changing pixels. */
internal object StaticHudDetector {
    fun suggest(frames: List<IntArray>, width: Int, height: Int): List<Region> {
        require(frames.size >= 4 && frames.all { it.size == width * height })
        val pixels = width * height
        val stride = 2
        val motion = FloatArray(pixels)
        for (i in 0 until pixels) {
            val mean = frames.sumOf { it[i] } / frames.size.toFloat()
            motion[i] = sqrt(frames.sumOf {
                val difference = it[i] - mean
                (difference * difference).toDouble()
            }.toFloat() / frames.size)
        }
        val candidates = BooleanArray(pixels)
        val horizontal = IntArray(frames.size)
        val vertical = IntArray(frames.size)
        for (y in stride until height - stride) {
            for (x in stride until width - stride) {
                val i = y * width + x
                if (motion[i] >= 14f) continue
                frames.forEachIndexed { index, frame ->
                    horizontal[index] = frame[i + stride] - frame[i - stride]
                    vertical[index] = frame[i + stride * width] - frame[i - stride * width]
                }
                horizontal.sort()
                vertical.sort()
                val middle = frames.size / 2
                if (max(abs(horizontal[middle]), abs(vertical[middle])) < 52) continue
                if (sequenceOf(-4, 0, 4).any { dy ->
                        sequenceOf(-4, 0, 4).any { dx ->
                            val yy = (y + dy).coerceIn(0, height - 1)
                            val xx = (x + dx).coerceIn(0, width - 1)
                            motion[yy * width + xx] > 19f
                        }
                    }) candidates[i] = true
            }
        }
        val visited = BooleanArray(pixels)
        val work = IntArray(pixels)
        val regions = mutableListOf<Pair<Int, Region>>()
        for (start in candidates.indices) {
            if (!candidates[start] || visited[start]) continue
            var count = 1
            var head = 0
            var x0 = width
            var x1 = 0
            var y0 = height
            var y1 = 0
            visited[start] = true
            work[0] = start
            while (head < count) {
                val index = work[head++]
                val x = index % width
                val y = index / width
                x0 = minOf(x0, x)
                x1 = maxOf(x1, x)
                y0 = minOf(y0, y)
                y1 = maxOf(y1, y)
                for (yy in maxOf(0, y - 2)..minOf(height - 1, y + 2)) {
                    for (xx in maxOf(0, x - 2)..minOf(width - 1, x + 2)) {
                        val neighbor = yy * width + xx
                        if (candidates[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true
                            work[count++] = neighbor
                        }
                    }
                }
            }
            val margin = max(3, ceil(minOf(width, height) * .015).toInt())
            val left = maxOf(0, x0 - margin) / width.toFloat()
            val top = maxOf(0, y0 - margin) / height.toFloat()
            val right = minOf(width, x1 + margin + 1) / width.toFloat()
            val bottom = minOf(height, y1 + margin + 1) / height.toFloat()
            val area = (right - left) * (bottom - top)
            if (count >= 5 && area in .00025f.. .06f) {
                regions += count to Region(left, top, right, bottom)
            }
        }
        return regions.sortedByDescending { it.first }.take(HudProfile.MAX_REGIONS).map { it.second }
    }
}
