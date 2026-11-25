package org.ghostcapture

internal class CaptureTimeline(private val startedUs: Long) {
    private val pauses = mutableListOf<Pair<Long, Long>>()
    private var pausedAt: Long? = null

    @Synchronized
    fun setPaused(paused: Boolean, atUs: Long) {
        if (paused && pausedAt == null) pausedAt = atUs
        if (!paused) {
            pausedAt?.let { pauses.add(it to atUs) }
            pausedAt = null
        }
    }

    data class Stamp(val timeUs: Long, val segment: Int)

    fun timestamp(atUs: Long): Long? = stamp(atUs)?.timeUs

    @Synchronized
    fun stamp(atUs: Long): Stamp? {
        if (atUs < startedUs || pausedAt?.let { atUs >= it } == true) return null
        var removed = 0L
        var segment = 0
        for ((start, end) in pauses) {
            if (atUs < start) break
            if (atUs < end) return null
            removed += end - start
            segment++
        }
        return Stamp(atUs - startedUs - removed, segment)
    }
}
