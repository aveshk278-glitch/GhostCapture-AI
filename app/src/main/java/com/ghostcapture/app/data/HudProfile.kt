package com.ghostcapture.app.data

/**
 * A HUD mask: one rectangle marking a HUD element to remove in
 * post-processing (Kaggle notebook / desktop tool).
 *
 * Coordinates are fractions of the frame (0..1), matching the JSON format
 * shared with the Kaggle notebook and the desktop CLI:
 * {"label": "...", "x": 0.02, "y": 0.72, "w": 0.14, "h": 0.18}
 */
data class HudMask(
    val label: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
) {
    /** Clamp the rectangle inside the frame. Pure function (unit-tested). */
    fun normalized(): HudMask {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        val cw = w.coerceIn(0f, 1f - cx)
        val ch = h.coerceIn(0f, 1f - cy)
        return copy(x = cx, y = cy, w = cw, h = ch)
    }
}

/**
 * A saved HUD profile for one game. Written as JSON so the Kaggle notebook
 * and the desktop tool can read the same file.
 */
data class HudProfile(
    val version: Int = 1,
    val game: String,
    val videoWidth: Int,
    val videoHeight: Int,
    val masks: List<HudMask>
) {
    fun normalized(): HudProfile = copy(masks = masks.map { it.normalized() })
}
