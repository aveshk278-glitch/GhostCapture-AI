package com.ghostcapture.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ghostcapture.app.data.HudProfile
import com.ghostcapture.app.data.HudProfileStore

@Composable
fun ProfilesScreen(profileStore: HudProfileStore) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf<List<HudProfile>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var toDelete by remember { mutableStateOf<HudProfile?>(null) }

    LaunchedEffect(refreshKey) {
        profiles = profileStore.listProfiles()
    }

    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete profile?") },
            text = { Text(toDelete?.game ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    val profile = toDelete
                    toDelete = null
                    if (profile != null && profileStore.deleteProfile(profile.game)) {
                        refreshKey++
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "HUD Profiles",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Mask sets saved per game. Export the JSON and use it with " +
                "the Kaggle notebook or the desktop tool.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (profiles.isEmpty()) {
            Text(
                text = "No HUD profiles yet.\nRecord a video, open it in the Gallery " +
                    "and tap \"Mask HUD\" to draw masks.",
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(profiles, key = { it.game }) { profile ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.game,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${profile.masks.size} masks  •  " +
                                        "${profile.videoWidth}×${profile.videoHeight}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = profile.masks.joinToString(", ") { it.label },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            IconButton(onClick = {
                                val json =
                                    profileStore.profileToJson(profile).toString(2)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "${profile.game} HUD profile")
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, "Export profile")
                                )
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Export")
                            }
                            IconButton(onClick = { toDelete = profile }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
