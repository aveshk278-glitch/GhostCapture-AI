package com.ghostcapture.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostcapture.app.data.RecorderSettings
import com.ghostcapture.app.data.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settingsStore: SettingsStore) {
    val settings by settingsStore.settings.collectAsState(initial = RecorderSettings())
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Video quality",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                RadioOption(
                    label = "720p",
                    selected = settings.quality == "720p",
                    onClick = { scope.launch { settingsStore.setQuality("720p") } }
                )
                RadioOption(
                    label = "1080p",
                    selected = settings.quality == "1080p",
                    onClick = { scope.launch { settingsStore.setQuality("1080p") } }
                )
            }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Frame rate",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                RadioOption(
                    label = "30 FPS",
                    selected = settings.fps == 30,
                    onClick = { scope.launch { settingsStore.setFps(30) } }
                )
                RadioOption(
                    label = "60 FPS",
                    selected = settings.fps == 60,
                    onClick = { scope.launch { settingsStore.setFps(60) } }
                )
                Text(
                    text = "60 FPS only works if the device supports it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Audio",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                RadioOption(
                    label = "Off",
                    selected = settings.audio == "off",
                    onClick = { scope.launch { settingsStore.setAudio("off") } }
                )
                RadioOption(
                    label = "Microphone",
                    selected = settings.audio == "mic",
                    onClick = { scope.launch { settingsStore.setAudio("mic") } }
                )
                Text(
                    text = "Device (internal game) audio is not in this version — " +
                        "it needs a MediaCodec + AudioPlaybackCapture pipeline " +
                        "(planned Phase 3). This app does not fake it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "3-second countdown before recording",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = settings.countdown,
                    onCheckedChange = { checked ->
                        scope.launch { settingsStore.setCountdown(checked) }
                    }
                )
            }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Storage",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Videos save to Movies/GhostCapture on the phone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "About — honest limits",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "GhostCapture AI 1.0\n\n" +
                        "• Records the screen with MediaProjection. No tap " +
                        "indicators are drawn by the app.\n" +
                        "• If Android's \"Show touches\" is on, its circles WILL " +
                        "appear in recordings — turn it off in Developer options.\n" +
                        "• HUD removal happens in post-processing (Kaggle GPU " +
                        "notebook / desktop tool), not live while recording.\n" +
                        "• No universal HUD-detection model exists — masks are " +
                        "manual per game, or template-based. Reconstructed " +
                        "pixels are an AI guess, not the original hidden game.\n" +
                        "• No internet permission, no account, no ads, no " +
                        "watermark, no subscription. Free and open source.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
