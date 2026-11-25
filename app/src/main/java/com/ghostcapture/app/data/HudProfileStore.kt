package com.ghostcapture.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads/writes HUD profiles as JSON files in the app's private files dir.
 * The JSON shape is shared with the Kaggle notebook and the desktop CLI.
 */
class HudProfileStore(context: Context) {

    private val dir: File = File(context.filesDir, "hud_profiles").apply { mkdirs() }

    fun profileFile(game: String): File {
        val safe = game.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "game" }
        return File(dir, "$safe.json")
    }

    fun listProfiles(): List<HudProfile> {
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<HudProfile>()
        for (file in files) {
            if (!file.name.endsWith(".json")) continue
            try {
                val profile = profileFromJson(JSONObject(file.readText()))
                if (profile != null) out.add(profile)
            } catch (e: Exception) {
                Log.w("HudProfileStore", "Skipping unreadable profile ${file.name}", e)
            }
        }
        out.sortBy { it.game.lowercase() }
        return out
    }

    fun loadProfile(game: String): HudProfile? {
        val file = profileFile(game)
        if (!file.exists()) return null
        return try {
            profileFromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.w("HudProfileStore", "Failed to load profile $game", e)
            null
        }
    }

    fun saveProfile(profile: HudProfile): Boolean {
        return try {
            profileFile(profile.game).writeText(profileToJson(profile.normalized()).toString(2))
            true
        } catch (e: Exception) {
            Log.w("HudProfileStore", "Failed to save profile ${profile.game}", e)
            false
        }
    }

    fun deleteProfile(game: String): Boolean {
        return try {
            profileFile(game).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun profileToJson(profile: HudProfile): JSONObject {
        val masks = JSONArray()
        for (mask in profile.masks) {
            masks.put(
                JSONObject()
                    .put("label", mask.label)
                    .put("x", mask.x.toDouble())
                    .put("y", mask.y.toDouble())
                    .put("w", mask.w.toDouble())
                    .put("h", mask.h.toDouble())
            )
        }
        return JSONObject()
            .put("version", profile.version)
            .put("game", profile.game)
            .put("videoWidth", profile.videoWidth)
            .put("videoHeight", profile.videoHeight)
            .put("masks", masks)
    }

    fun profileFromJson(json: JSONObject): HudProfile? {
        return try {
            val masks = ArrayList<HudMask>()
            val arr = json.optJSONArray("masks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                masks.add(
                    HudMask(
                        label = o.optString("label", "hud"),
                        x = o.optDouble("x", 0.0).toFloat(),
                        y = o.optDouble("y", 0.0).toFloat(),
                        w = o.optDouble("w", 0.0).toFloat(),
                        h = o.optDouble("h", 0.0).toFloat()
                    ).normalized()
                )
            }
            HudProfile(
                version = json.optInt("version", 1),
                game = json.optString("game", "game"),
                videoWidth = json.optInt("videoWidth", 0),
                videoHeight = json.optInt("videoHeight", 0),
                masks = masks
            )
        } catch (e: Exception) {
            Log.w("HudProfileStore", "Malformed profile JSON", e)
            null
        }
    }
}
