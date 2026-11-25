package com.ghostcapture.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ghostcapture.app.data.HudMask
import com.ghostcapture.app.data.HudProfile
import com.ghostcapture.app.data.HudProfileStore
import com.ghostcapture.app.ui.theme.GhostAmber
import com.ghostcapture.app.ui.theme.GhostCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LABEL_PRESETS = listOf(
    "joystick", "fire_button", "minimap", "health_bar",
    "ammo", "crosshair", "kill_feed", "other"
)

/** Rect of the displayed image inside a box (ContentScale.Fit). */
private fun displayRect(boxW: Float, boxH: Float, imgW: Float, imgH: Float): Rect {
    if (boxW <= 0f || boxH <= 0f || imgW <= 0f || imgH <= 0f) {
        return Rect(0f, 0f, boxW, boxH)
    }
    val scale = minOf(boxW / imgW, boxH / imgH)
    val dispW = imgW * scale
    val dispH = imgH * scale
    val offX = (boxW - dispW) / 2f
    val offY = (boxH - dispH) / 2f
    return Rect(offX, offY, offX + dispW, offY + dispH)
}

/** Convert a box-space offset to normalized image coordinates. */
private fun toNormalized(offset: Offset, rect: Rect): Offset {
    if (rect.width <= 0f || rect.height <= 0f) return Offset.Zero
    return Offset(
        ((offset.x - rect.left) / rect.width).coerceIn(0f, 1f),
        ((offset.y - rect.top) / rect.height).coerceIn(0f, 1f)
    )
}

@Composable
fun MaskEditorScreen(
    videoUri: String,
    game: String,
    profileStore: HudProfileStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var gameName by remember { mutableStateOf(game) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedLabel by remember { mutableStateOf("joystick") }
    var customLabel by remember { mutableStateOf("") }
    val masks = remember { mutableStateListOf<HudMask>() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(videoUri) {
        val result = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(videoUri))
                val w = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                val bmp = retriever.getFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                Triple(w, h, bmp)
            } catch (e: Exception) {
                Triple(0, 0, null)
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Nothing to do — retriever is being released anyway.
                }
            }
        }
        videoWidth = result.first
        videoHeight = result.second
        if (result.third != null) {
            frame = result.third
        } else {
            loadError = "Could not read a frame from this video."
        }
    }

    LaunchedEffect(game) {
        val existing = profileStore.loadProfile(game)
        if (existing != null) {
            masks.clear()
            masks.addAll(existing.masks)
            message = "Loaded existing profile: ${existing.masks.size} masks."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "HUD Mask Editor",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedTextField(
            value = gameName,
            onValueChange = { gameName = it },
            label = { Text("Game name") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { coordinates ->
                    boxSize = coordinates.size
                }
                .pointerInput(boxSize, frame) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragCurrent = offset
                        },
                        onDragEnd = {
                            val start = dragStart
                            val current = dragCurrent
                            dragStart = null
                            dragCurrent = null
                            val img = frame
                            if (start != null && current != null &&
                                boxSize.width > 0 && boxSize.height > 0 && img != null
                            ) {
                                val rect = displayRect(
                                    boxSize.width.toFloat(),
                                    boxSize.height.toFloat(),
                                    img.width.toFloat(),
                                    img.height.toFloat()
                                )
                                val n1 = toNormalized(start, rect)
                                val n2 = toNormalized(current, rect)
                                val label = if (selectedLabel == "other") {
                                    customLabel.trim().ifEmpty { "hud" }
                                } else {
                                    selectedLabel
                                }
                                val mask = HudMask(
                                    label = label,
                                    x = minOf(n1.x, n2.x),
                                    y = minOf(n1.y, n2.y),
                                    w = kotlin.math.abs(n1.x - n2.x),
                                    h = kotlin.math.abs(n1.y - n2.y)
                                ).normalized()
                                if (mask.w > 0.01f && mask.h > 0.01f) {
                                    masks.add(mask)
                                }
                            }
                        },
                        onDragCancel = {
                            dragStart = null
                            dragCurrent = null
                        },
                        onDrag = { _, amount ->
                            val cur = dragCurrent
                            if (cur != null) {
                                dragCurrent = cur + amount
                            }
                        }
                    )
                }
        ) {
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Video frame",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = loadError ?: "Loading frame…",
                    modifier = Modifier.padding(16.dp)
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val img = frame
                val rect = if (img != null) {
                    displayRect(
                        size.width, size.height,
                        img.width.toFloat(), img.height.toFloat()
                    )
                } else {
                    Rect(0f, 0f, size.width, size.height)
                }
                for (mask in masks) {
                    val left = rect.left + mask.x * rect.width
                    val top = rect.top + mask.y * rect.height
                    drawRect(
                        color = GhostCyan,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(
                            mask.w * rect.width,
                            mask.h * rect.height
                        ),
                        style = Stroke(width = 3f)
                    )
                }
                val start = dragStart
                val current = dragCurrent
                if (start != null && current != null) {
                    val n1 = toNormalized(start, rect)
                    val n2 = toNormalized(current, rect)
                    val left = rect.left + minOf(n1.x, n2.x) * rect.width
                    val top = rect.top + minOf(n1.y, n2.y) * rect.height
                    drawRect(
                        color = GhostAmber,
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(
                            kotlin.math.abs(n1.x - n2.x) * rect.width,
                            kotlin.math.abs(n1.y - n2.y) * rect.height
                        ),
                        style = Stroke(width = 3f)
                    )
                }
            }
        }

        Text(
            text = "Drag on the frame to draw a rectangle over each HUD element.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (label in LABEL_PRESETS) {
                FilterChip(
                    selected = selectedLabel == label,
                    onClick = { selectedLabel = label },
                    label = { Text(label) }
                )
            }
        }
        if (selectedLabel == "other") {
            OutlinedTextField(
                value = customLabel,
                onValueChange = { customLabel = it },
                label = { Text("Custom label") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            itemsIndexed(masks) { index, mask ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}. ${mask.label}  " +
                            "(${"%.0f".format(mask.x * 100)}%, ${"%.0f".format(mask.y * 100)}%) " +
                            "${"%.0f".format(mask.w * 100)}×${"%.0f".format(mask.h * 100)}%",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(onClick = { masks.removeAt(index) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }

        if (message != null) {
            Text(
                text = message ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = GhostCyan,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (videoWidth <= 0 || videoHeight <= 0) {
                        message = "Video size unknown — cannot save yet."
                        return@Button
                    }
                    val profile = HudProfile(
                        game = gameName.trim().ifEmpty { "game" },
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                        masks = masks.toList()
                    )
                    scope.launch(Dispatchers.IO) {
                        val ok = profileStore.saveProfile(profile)
                        withContext(Dispatchers.Main) {
                            message = if (ok) {
                                "Profile saved: ${masks.size} masks for ${profile.game}."
                            } else {
                                "Failed to save profile."
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save profile")
            }
            OutlinedButton(
                onClick = {
                    val profile = HudProfile(
                        game = gameName.trim().ifEmpty { "game" },
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                        masks = masks.toList()
                    )
                    val json = profileStore.profileToJson(profile).toString(2)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${profile.game} HUD profile")
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Export profile")
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Export JSON")
            }
        }
    }
}
