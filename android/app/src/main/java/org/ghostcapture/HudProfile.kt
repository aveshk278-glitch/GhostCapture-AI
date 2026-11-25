package org.ghostcapture

import org.json.JSONArray
import org.json.JSONObject

internal data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f)
        require(right - left >= .005f && bottom - top >= .005f)
        require(left > 0f || top > 0f || right < 1f || bottom < 1f)
    }
}

internal object HudProfile {
    const val MAX_REGIONS = 8

    fun encode(regions: List<Region>): String = JSONObject()
        .put("version", 1)
        .put("rectangles", JSONArray().apply {
            regions.forEach { put(JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
        }).toString(2)

    fun decode(json: String): List<Region> {
        val profile = JSONObject(json)
        require(profile.getInt("version") == 1) { "Unsupported HUD profile version." }
        val list = profile.getJSONArray("rectangles")
        require(list.length() <= MAX_REGIONS) { "Android supports up to eight regions per profile." }
        return List(list.length()) { i ->
            val rect = list.getJSONArray(i)
            require(rect.length() == 4)
            Region(rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(),
                rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat())
        }
    }
}
