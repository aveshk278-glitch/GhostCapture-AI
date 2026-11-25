package com.ghostcapture.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ghostcapture_settings"
)

data class RecorderSettings(
    val quality: String = "1080p",
    val fps: Int = 30,
    val audio: String = "off", // "off" or "mic"
    val countdown: Boolean = true
)

/** Recording resolution for a quality label. */
fun resolutionForQuality(quality: String): Pair<Int, Int> {
    return when (quality) {
        "720p" -> Pair(1280, 720)
        else -> Pair(1920, 1080)
    }
}

/** Video bitrate (bits/sec) for a quality label. */
fun bitrateForQuality(quality: String): Int {
    return when (quality) {
        "720p" -> 8_000_000
        else -> 16_000_000
    }
}

class SettingsStore(private val context: Context) {

    private val qualityKey = stringPreferencesKey("quality")
    private val fpsKey = intPreferencesKey("fps")
    private val audioKey = stringPreferencesKey("audio")
    private val countdownKey = booleanPreferencesKey("countdown")

    val settings: Flow<RecorderSettings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            RecorderSettings(
                quality = prefs[qualityKey] ?: "1080p",
                fps = prefs[fpsKey] ?: 30,
                audio = prefs[audioKey] ?: "off",
                countdown = prefs[countdownKey] ?: true
            )
        }

    suspend fun setQuality(quality: String) {
        context.dataStore.edit { it[qualityKey] = quality }
    }

    suspend fun setFps(fps: Int) {
        context.dataStore.edit { it[fpsKey] = fps }
    }

    suspend fun setAudio(audio: String) {
        context.dataStore.edit { it[audioKey] = audio }
    }

    suspend fun setCountdown(enabled: Boolean) {
        context.dataStore.edit { it[countdownKey] = enabled }
    }
}
