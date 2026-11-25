package com.ghostcapture.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostcapture.app.data.RecorderSettings
import com.ghostcapture.app.data.SettingsStore
import com.ghostcapture.app.recorder.RecorderState
import com.ghostcapture.app.ui.theme.GhostAmber
import com.ghostcapture.app.ui.theme.GhostCyan

@Composable
fun HomeScreen(
    settingsStore: SettingsStore,
    onRecordRequested: (RecorderSettings) -> Unit,
    onStopRequested: () -> Unit,
    onPauseRequested: () -> Unit,
    onResumeRequested: () -> Unit,
    showTouchesOn: Boolean,
    onOpenDevSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenProfiles: () -> Unit
) {
    val settings by settingsStore.settings.collectAsState(initial = RecorderSettings())
    val isRecording by RecorderState.isRecording.collectAsState()
    val isPaused by RecorderState.isPaused.collectAsState()
    val elapsedMs by RecorderState.elapsedMs.collectAsState()
    val error by RecorderState.error.collectAsState()
    val notice by RecorderState.notice.collectAsState()
    val lastSavedUri by RecorderState.lastSavedUri.collectAsState()
    val currentFileName by RecorderState.currentFileName.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "GhostCapture AI",
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = "Record Everything. Reveal Only Gameplay.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (error != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error ?: "",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { RecorderState.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }
        if (notice != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notice ?: "",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { RecorderState.clearNotice() }) {
                        Text("OK")
                    }
                }
            }
        }

        if (showTouchesOn) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GhostAmber.copy(alpha = 0.18f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = GhostAmber
                        )
                        Text(
                            text = "  Show touches is ON",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Android's \"Show touches\" developer option is on. " +
                            "Tap circles drawn by the system WILL appear in recordings — " +
                            "no recorder can remove them. Turn it off before recording.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenDevSettings) {
                        Text("Open Developer options")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isRecording) {
                    Text(
                        text = if (isPaused) "Paused" else "Recording",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isPaused) GhostAmber else GhostCyan
                    )
                    Text(
                        text = formatElapsed(elapsedMs),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentFileName ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (isPaused) onResumeRequested() else onPauseRequested()
                            }
                        ) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                            Text(if (isPaused) "Resume" else "Pause")
                        }
                        Button(
                            onClick = onStopRequested,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Text("Stop")
                        }
                    }
                    Text(
                        text = "Stop is also in the recording notification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Ready to record",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onRecordRequested(settings) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("  Record", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The record button hides once recording starts — " +
                            "nothing from this app appears in the video.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(settings.quality) })
            AssistChip(onClick = {}, label = { Text("${settings.fps} FPS") })
            AssistChip(
                onClick = {},
                label = { Text(if (settings.audio == "mic") "Mic audio" else "No audio") }
            )
        }

        if (lastSavedUri != null) {
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Video saved: ${currentFileName ?: ""}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val uri = lastSavedUri ?: return@OutlinedButton
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null)
                            Text("Open")
                        }
                        OutlinedButton(onClick = {
                            val uri: Uri = lastSavedUri ?: return@OutlinedButton
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share video")
                            )
                        }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Text("Share")
                        }
                    }
                }
            }
        }

        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "How HUD removal works",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1. Record your gameplay normally (HUD visible while you play).\n" +
                        "2. Open the video in the Gallery and draw masks over the HUD " +
                        "in the Mask Editor — saved as a profile per game.\n" +
                        "3. Process the video with the free Kaggle GPU notebook or the " +
                        "desktop tool. They inpaint the masked areas.\n\n" +
                        "A recorder alone cannot hide a game's HUD — the HUD is drawn " +
                        "by the game. Removal happens in post-processing, not live. " +
                        "Live AI inpainting on a phone is not feasible.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onOpenGallery,
                modifier = Modifier.weight(1f)
            ) {
                Text("Gallery")
            }
            OutlinedButton(
                onClick = onOpenProfiles,
                modifier = Modifier.weight(1f)
            ) {
                Text("HUD Profiles")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
