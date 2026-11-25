package org.ghostcapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.BatteryManager
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.effect.Presentation
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private data class Settings(val size: CaptureSize, val fps: Int, val mime: String, val audio: AudioSource, val bitrate: Int)
    private data class Clip(val uri: Uri, val start: Long = 0, val end: Long? = null)
    private data class ExportSettings(val height: Int?, val bitrate: Int?, val volume: Float)
    private data class ExportTask(val clips: List<Clip>, val masks: List<Region>, val settings: ExportSettings)

    private var pendingCapture: Settings? = null
    private var launchOnCapture: String? = null
    private var mergeInput by mutableStateOf<Uri?>(null)
    private val exportQueue = ArrayDeque<ExportTask>()
    private var pendingCopy: File? = null
    private var chosen by mutableStateOf<Uri?>(null)
    private var latest by mutableStateOf<File?>(null)
    private var pendingOutput: File? = null
    private var regions by mutableStateOf<List<Region>>(emptyList())
    private var transformer by mutableStateOf<Transformer?>(null)
    private var status by mutableStateOf("")
    private var libraryVersion by mutableIntStateOf(0)
    private val folder get() = CaptureService.recordings(filesDir)

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val settings = pendingCapture
        pendingCapture = null
        if (result.resultCode != RESULT_OK || result.data == null || settings == null) {
            launchOnCapture = null
            status = "Screen capture consent was cancelled."
        } else {
            val intent = Intent(this, CaptureService::class.java).setAction("start")
                .putExtra("token", result.data).putExtra("width", settings.size.width)
                .putExtra("height", settings.size.height).putExtra("fps", settings.fps)
                .putExtra("mime", settings.mime).putExtra("audio", settings.audio.ordinal)
                .putExtra("bitrate", settings.bitrate)
            runCatching { startForegroundService(intent) }
                .onFailure { status = it.message ?: "Android rejected screen capture." }
        }
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestCapture() else {
            pendingCapture = null
            launchOnCapture = null
            status = "Audio access was declined. Select silent capture to continue."
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val importVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            chosen = it
            mergeInput = null
            latest = null
        }
    }
    private val importMergeVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { mergeInput = it }
    }
    private val importProfile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                val json = contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = ByteArray(65537)
                    var length = 0
                    while (length < bytes.size) {
                        val read = stream.read(bytes, length, bytes.size - length)
                        if (read < 0) break
                        length += read
                    }
                    require(length < bytes.size) { "HUD profile exceeds 64 KiB." }
                    bytes.decodeToString(0, length)
                }
                regions = HudProfile.decode(requireNotNull(json))
            }.onFailure { error -> status = error.message ?: "Invalid HUD profile." }
        }
    }
    private val exportProfile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            runCatching {
                requireNotNull(contentResolver.openOutputStream(it)).use { stream ->
                    stream.write(HudProfile.encode(regions).encodeToByteArray())
                }
            }.onFailure { error -> status = error.message ?: "Could not write HUD profile." }
        }
    }
    private val exportVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = pendingCopy
        pendingCopy = null
        if (uri != null && file != null) {
            Thread {
                val result = runCatching {
                    requireNotNull(contentResolver.openOutputStream(uri)).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
                runOnUiThread { status = result.exceptionOrNull()?.message ?: "Video copied." }
            }.start()
        }
    }

    private fun requestCapture() {
        capturePermission.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCapture = savedInstanceState?.getIntArray("capture")?.let { values ->
            Settings(CaptureSize(values[0], values[1]), values[2],
                if (values[3] == 1) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                AudioSource.entries[values[4]], values[5])
        }
        pendingCopy = savedInstanceState?.getString("copy")?.let(::File)
        launchOnCapture = savedInstanceState?.getString("launchGame")
        chosen = savedInstanceState?.getString("chosen")?.let(Uri::parse)
        latest = savedInstanceState?.getString("latest")?.let(::File)?.takeIf { it.isFile }
        regions = runCatching { HudProfile.decode(getPreferences(0).getString("profile", "")!!) }.getOrDefault(emptyList())
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF67F4EB), secondary = Color(0xFFC9ABFF),
                background = Color(0xFF030306), surface = Color(0xFF141321)
            )) { Surface(Modifier.fillMaxSize()) { Dashboard() } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCapture?.let {
            outState.putIntArray("capture", intArrayOf(it.size.width, it.size.height, it.fps,
                if (it.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 1 else 0, it.audio.ordinal, it.bitrate))
        }
        outState.putString("copy", pendingCopy?.path)
        outState.putString("launchGame", launchOnCapture)
        outState.putString("chosen", chosen?.toString())
        outState.putString("latest", latest?.path)
    }

    override fun onDestroy() {
        transformer?.cancel()
        pendingOutput?.delete()
        super.onDestroy()
    }

    private fun editedClip(input: Clip, task: ExportTask): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(input.start)
        input.end?.let { clip.setEndPositionMs(it) }
        val media = MediaItem.Builder().setUri(input.uri).setClippingConfiguration(clip.build()).build()
        val video = buildList {
            if (task.masks.isNotEmpty()) add(HudFillEffect(task.masks))
            task.settings.height?.let { add(Presentation.createForHeight(it)) }
        }
        val audio = if (task.settings.volume >= 1f) emptyList() else listOf(ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) putChannelMixingMatrix(
                ChannelMixingMatrix.create(channels, channels).scaleBy(task.settings.volume)
            )
        })
        return EditedMediaItem.Builder(media).setEffects(Effects(audio, video)).build()
    }

    private fun startExport(task: ExportTask) {
        val temporary = File(folder, "${UUID.randomUUID()}.exporting")
        pendingOutput = temporary
        val finalFile = File(folder, "${temporary.nameWithoutExtension}-edited.mp4")
        val items = task.clips.map { editedClip(it, task) }
        val builder = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264)
        task.settings.bitrate?.let { bitrate ->
            val encoder = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()
            builder.setEncoderFactory(encoder)
        }
        val job = builder.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                transformer = null
                pendingOutput = null
                if (temporary.renameTo(finalFile)) {
                    latest = finalFile
                    libraryVersion++
                    val next = exportQueue.removeFirstOrNull()
                    if (next == null) status = "Export complete."
                    else {
                        status = "Exporting the second clip."
                        startExport(next)
                    }
                } else {
                    temporary.delete()
                    exportQueue.clear()
                    status = "Could not finalize the export."
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                transformer = null
                pendingOutput = null
                temporary.delete()
                exportQueue.clear()
                status = exportException.message ?: "Export failed."
            }
        }).build()
        transformer = job
        runCatching {
            if (items.size == 1) job.start(items.single(), temporary.path)
            else {
                val sequence = EditedMediaItemSequence.Builder(items).build()
                val composition = Composition.Builder(sequence).experimentalSetForceAudioTrack(true).build()
                job.start(composition, temporary.path)
            }
        }.onFailure {
            exportQueue.clear()
            transformer = null
            pendingOutput = null
            temporary.delete()
            status = it.message ?: "Export could not start."
        }
    }

    @Composable
    private fun Dashboard() {
        val state by CaptureService.state.collectAsState()
        var tab by remember { mutableStateOf("Capture") }
        var fps by remember { mutableIntStateOf(30) }
        var quality by remember { mutableIntStateOf(720) }
        var hevc by remember { mutableStateOf(false) }
        var portrait by remember { mutableStateOf(false) }
        var audio by remember { mutableStateOf(AudioSource.SILENT) }
        var bitrate by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf<File?>(null) }
        var chooseGame by remember { mutableStateOf(false) }
        var game by remember { mutableStateOf(getPreferences(0).getString("game", null)) }
        var games by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var countdown by remember { mutableIntStateOf(0) }
        val captureScope = rememberCoroutineScope()
        LaunchedEffect(chooseGame) {
            if (chooseGame) games = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcher, 0).map { entry ->
                    entry.activityInfo.packageName to entry.loadLabel(packageManager).toString()
                }.filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
            }
        }
        LaunchedEffect(state.active, state.message) {
            if (state.active) {
                val target = launchOnCapture
                launchOnCapture = null
                if (target != null) {
                    val intent = packageManager.getLaunchIntentForPackage(target)
                    if (intent != null) runCatching { startActivity(intent) }
                        .onFailure { status = "Open your game from the launcher." }
                    else status = "The selected game is unavailable. Open a game from the launcher."
                }
            } else if (state.message.isNotBlank()) launchOnCapture = null
        }
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val dimensions = resources.displayMetrics
        val aspect = max(dimensions.widthPixels, dimensions.heightPixels).toFloat() /
            min(dimensions.widthPixels, dimensions.heightPixels).coerceAtLeast(1)
        fun size(short: Int): CaptureSize {
            val long = (short * aspect / 2).roundToInt() * 2
            return if (portrait) CaptureSize(short, long) else CaptureSize(long, short)
        }
        val supported = remember(aspect, mime, portrait) {
            listOf(480, 720, 1080, 1440, 2160).associateWith { height ->
                listOf(30, 60, 90, 120).filter { CaptureService.supported(size(height), it, mime) }
            }
        }
        val files = remember(state.active, libraryVersion) {
            folder.listFiles { file -> file.extension == "mp4" || file.extension == "recording" }
                .orEmpty().filter { it.extension == "mp4" || !state.active }.sortedByDescending { it.lastModified() }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Text("GhostCapture AI", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Capture", "Editor", "Gallery", "Help").forEach { name ->
                    FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) })
                }
            }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary)
            if (state.message.isNotBlank()) Text(state.message)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    "Capture" -> {
                        val battery = getSystemService(BatteryManager::class.java)
                            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val thermal = getSystemService(PowerManager::class.java).currentThermalStatus
                        val storage = StatFs(filesDir.path).availableBytes / (1024 * 1024)
                        Text("Free storage ${storage} MiB  ·  Battery $battery%  ·  Thermal status $thermal")
                        if (state.active) {
                            val seconds = state.elapsedMillis / 1000
                            val timer = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
                            Text(timer, style = MaterialTheme.typography.headlineMedium)
                            Button(onClick = { service(if (state.paused) "resume" else "pause") }) {
                                Text(if (state.paused) "Resume recording" else "Pause recording")
                            }
                            Button(onClick = { service("stop") }) { Text("Stop and save") }
                        } else {
                            OutlinedButton(onClick = { chooseGame = true }, enabled = countdown == 0) {
                                val label = games.firstOrNull { it.first == game }?.second ?: game
                                Text(label?.let { "Game · $it" } ?: "Choose a game to launch")
                            }
                            Text("Orientation")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!portrait, { portrait = false }, { Text("Landscape") })
                                FilterChip(portrait, { portrait = true }, { Text("Portrait") })
                            }
                            Text("Codec")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!hevc, { hevc = false }, { Text("H.264") })
                                FilterChip(hevc, { hevc = true }, { Text("H.265") })
                            }
                            Choices("Short edge", listOf(480, 720, 1080, 1440, 2160), quality,
                                { supported[it].orEmpty().isNotEmpty() }) { quality = it; fps = supported[it]!!.first() }
                            Choices("Frames per second", listOf(30, 60, 90, 120), fps,
                                { it in supported[quality].orEmpty() }) { fps = it }
                            OutlinedTextField(bitrate, { bitrate = it }, label = { Text("Bitrate in Mb/s (blank for automatic)") })
                            Text("Audio source")
                            AudioSource.entries.chunked(2).forEach { options ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    options.forEach { option ->
                                        FilterChip(audio == option, { audio = option }, { Text(option.label) })
                                    }
                                }
                            }
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                    Text("Enable notification controls")
                                }
                            }
                            val captureSize = size(quality)
                            val bits = if (bitrate.isBlank()) {
                                (captureSize.width.toLong() * captureSize.height * fps / 7).toInt()
                            } else {
                                bitrate.toDoubleOrNull()?.takeIf { it in .1..200.0 }?.let { (it * 1_000_000).toInt() }
                            }
                            Button(
                                enabled = countdown == 0 && fps in supported[quality].orEmpty() && bits != null,
                                onClick = {
                                    status = ""
                                    val settings = Settings(captureSize, fps, mime, audio, requireNotNull(bits))
                                    launchOnCapture = game
                                    countdown = 3
                                    captureScope.launch {
                                        while (countdown > 0) {
                                            delay(1000)
                                            countdown--
                                        }
                                        pendingCapture = settings
                                        if (settings.audio != AudioSource.SILENT && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                        } else requestCapture()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) { Text(if (countdown > 0) "Requesting capture in $countdown" else "Start recording") }
                        }
                    }
                    "Editor" -> Editor()
                    "Help" -> {
                        Text("Capture and permissions", style = MaterialTheme.typography.titleLarge)
                        Text("Approve Android screen sharing. Game audio requires permission from the game. Grant notifications for controls while playing, or return here to stop.")
                        Text("HUD reconstruction", style = MaterialTheme.typography.titleLarge)
                        Text("Review suggested rectangles before export. The editor estimates hidden pixels from nearby image data. Smaller, precise masks give it more usable context.")
                        Text("Desktop and GPU processing", style = MaterialTheme.typography.titleLarge)
                        Text("Save a recording and a HUD profile to a folder you can copy to your computer. The source package includes the desktop processor and optional Kaggle and Colab notebooks.")
                        Text("About GhostCapture AI", style = MaterialTheme.typography.titleLarge)
                        Text("Version 0.1.0 · Apache License 2.0")
                        Text("AndroidX, Kotlin and Gradle also use Apache 2.0. Full dependencies and model terms are listed in LICENSES.md in the source package.")
                    }
                    "Gallery" -> {
                        if (files.isEmpty()) Text("Record a video to populate your gallery.")
                        files.forEach { file ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(if (file.extension == "recording") "Interrupted recording" else
                                        if (file.name.endsWith("-edited.mp4")) "Edited video" else "Recording")
                                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified())))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { chosen = Uri.fromFile(file); latest = null; mergeInput = null; tab = "Editor" }) { Text("Open") }
                                        TextButton(onClick = { pendingCopy = file; exportVideo.launch(file.nameWithoutExtension + ".mp4") }) { Text("Save a copy") }
                                        TextButton(onClick = { deleting = file }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (chooseGame) {
            fun select(packageId: String?) {
                game = packageId
                getPreferences(0).edit().putString("game", packageId).apply()
                chooseGame = false
            }
            AlertDialog(
                onDismissRequest = { chooseGame = false },
                title = { Text("Choose an installed game") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { select(null) }) { Text("Open a game manually") }
                        games.forEach { (id, label) ->
                            TextButton(onClick = { select(id) }) { Text(label) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { chooseGame = false }) { Text("Close") } }
            )
        }
        deleting?.let { file ->
            AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this file?") },
                confirmButton = {
                    TextButton(onClick = {
                        if (file.delete()) {
                            if (chosen == Uri.fromFile(file)) chosen = null
                            if (latest == file) latest = null
                            libraryVersion++
                        } else status = "Could not delete the file."
                        deleting = null
                    }) { Text("Delete") }
                }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
        }
    }

    private fun service(action: String) {
        startService(Intent(this, CaptureService::class.java).setAction(action))
    }

    @Composable
    private fun Choices(label: String, values: List<Int>, selected: Int, available: (Int) -> Boolean, pick: (Int) -> Unit) {
        Text(label)
        values.chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { value -> FilterChip(value == selected, { pick(value) }, { Text(value.toString()) }, enabled = available(value)) }
            }
        }
    }

    @Composable
    private fun Editor() {
        val uri = chosen
        val job = transformer
        var progress by remember(job) { mutableIntStateOf(-1) }
        LaunchedEffect(job) {
            if (job != null) {
                val holder = ProgressHolder()
                while (true) {
                    progress = if (job.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
                    delay(500)
                }
            }
        }
        LaunchedEffect(regions) { getPreferences(0).edit().putString("profile", HudProfile.encode(regions)).apply() }
        OutlinedButton(onClick = { importVideo.launch(arrayOf("video/*")) }, enabled = job == null) { Text("Import video") }
        if (uri == null) return
        var duration by remember(uri) { mutableLongStateOf(0) }
        var time by remember(uri) { mutableFloatStateOf(0f) }
        var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
        var start by remember(uri) { mutableStateOf("0") }
        var end by remember(uri) { mutableStateOf("") }
        var play by remember(uri) { mutableStateOf(false) }
        var showAfter by remember(uri) { mutableStateOf(false) }
        var detecting by remember(uri) { mutableStateOf(false) }
        val editorScope = rememberCoroutineScope()
        var exportHeight by remember(uri) { mutableIntStateOf(0) }
        var exportBitrate by remember(uri) { mutableStateOf("") }
        var volume by remember(uri) { mutableFloatStateOf(1f) }
        LaunchedEffect(uri, time) {
            delay(100)
            val preview = withContext(Dispatchers.IO) {
                runCatching {
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(this@MainActivity, uri)
                        val length = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val w = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 960
                        val h = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 540
                        val ratio = 960f / max(w, h).coerceAtLeast(1)
                        length to reader.getScaledFrameAtTime((time * length * 1000).toLong(),
                            MediaMetadataRetriever.OPTION_CLOSEST, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
                    }
                }.getOrNull()
            }
            duration = preview?.first ?: 0
            bitmap = preview?.second
        }
        if (latest != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!showAfter, { showAfter = false }, { Text("Original") })
                FilterChip(showAfter, { showAfter = true; play = true }, { Text("Processed") })
            }
        }
        TextButton(onClick = { play = !play; if (!play) showAfter = false }) { Text(if (play) "Edit masks" else "Play video") }
        if (play) {
            VideoPlayer(if (showAfter) latest?.let { Uri.fromFile(it) } ?: uri else uri)
        } else {
            val frame = bitmap
            if (frame == null) Text("Preview unavailable for this video.")
            else {
                MaskCanvas(frame, job == null)
                if (duration > 0) Slider(time, { time = it }, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("Draw rectangles over HUD elements. Neighboring pixels are used to estimate the hidden image. Large regions can leave visible artifacts.")
        OutlinedButton(onClick = {
            val frame = requireNotNull(bitmap)
            val scale = 240f / max(frame.width, frame.height)
            val width = (frame.width * scale).roundToInt().coerceAtLeast(16)
            val height = (frame.height * scale).roundToInt().coerceAtLeast(16)
            detecting = true
            editorScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        MediaMetadataRetriever().use { reader ->
                            reader.setDataSource(this@MainActivity, uri)
                            val samples = (1..8).mapNotNull { index ->
                                reader.getScaledFrameAtTime(duration * 1000 * index / 9,
                                    MediaMetadataRetriever.OPTION_CLOSEST, width, height)?.let { sample ->
                                    val buffer = IntArray(width * height)
                                    val scaled = if (sample.width == width && sample.height == height) sample
                                    else Bitmap.createScaledBitmap(sample, width, height, true)
                                    scaled.getPixels(buffer, 0, width, 0, 0, width, height)
                                    for (i in buffer.indices) {
                                        val color = buffer[i]
                                        buffer[i] = ((color shr 16 and 255) * 77 +
                                            (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8
                                    }
                                    buffer
                                }
                            }
                            StaticHudDetector.suggest(samples, width, height)
                        }
                    }
                }
                detecting = false
                result.onSuccess { suggestions ->
                    val free = HudProfile.MAX_REGIONS - regions.size
                    regions = regions + suggestions.take(free)
                    status = if (suggestions.isEmpty()) "No static HUD candidates found. Draw masks manually."
                    else "Review the suggested HUD rectangles before export."
                }.onFailure { status = it.message ?: "Could not analyze the video." }
            }
        }, enabled = bitmap != null && duration > 0 && job == null && !detecting && !play &&
            regions.size < HudProfile.MAX_REGIONS) {
            Text(if (detecting) "Scanning frames…" else "Suggest static HUD masks")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { regions = regions.dropLast(1) }, enabled = job == null && regions.isNotEmpty()) { Text("Undo mask") }
            TextButton(onClick = { importProfile.launch(arrayOf("application/json", "text/plain")) }, enabled = job == null) { Text("Import profile") }
            TextButton(onClick = { exportProfile.launch("hud-profile.json") }, enabled = regions.isNotEmpty()) { Text("Save profile") }
        }
        OutlinedTextField(start, { start = it }, label = { Text("Trim start (ms)") })
        OutlinedTextField(end, { end = it }, label = { Text("Trim end (ms, optional)") })
        Choices("Export height (0 keeps the input size)", listOf(0, 480, 720, 1080), exportHeight, { true }) {
            exportHeight = it
        }
        OutlinedTextField(exportBitrate, { exportBitrate = it }, label = { Text("Export bitrate in Mb/s (optional)") })
        Text("Export volume ${(volume * 100).roundToInt()}%")
        Slider(volume, { volume = it })
        val bits = exportBitrate.toDoubleOrNull()?.takeIf { it in .1..200.0 }?.let { (it * 1_000_000).toInt() }
        val exportOptions = ExportSettings(exportHeight.takeIf { it > 0 }, bits, volume)
        val from = start.toLongOrNull()
        val to = end.toLongOrNull()
        val valid = from != null && from >= 0 && from < duration &&
            (end.isBlank() || to != null && to > from && to <= duration) &&
            (exportBitrate.isBlank() || bits != null)
        if (job != null) {
            Text(if (progress < 0) "Export in progress" else "Export $progress%")
            TextButton(onClick = {
                job.cancel()
                exportQueue.clear()
                transformer = null
                pendingOutput?.delete()
                pendingOutput = null
                status = "Export cancelled."
            }) { Text("Cancel export") }
        } else {
            fun primary() = Clip(uri, requireNotNull(from), to)
            Button(enabled = valid && regions.isNotEmpty(), onClick = {
                startExport(ExportTask(listOf(primary()), regions, exportOptions))
            }) { Text("Fill HUD regions and export") }
            OutlinedButton(enabled = valid, onClick = {
                startExport(ExportTask(listOf(primary()), emptyList(), exportOptions))
            }) { Text("Export trim") }
            val split = (time * duration).toLong()
            OutlinedButton(enabled = valid && split > 0 && split < duration, onClick = {
                exportQueue.add(ExportTask(listOf(Clip(uri, split)), emptyList(), exportOptions))
                startExport(ExportTask(listOf(Clip(uri, end = split)), emptyList(), exportOptions))
            }) { Text("Split at preview frame") }
            OutlinedButton(onClick = { importMergeVideo.launch(arrayOf("video/*")) }) {
                Text(if (mergeInput == null) "Choose video to append" else "Change video to append")
            }
            mergeInput?.let { other ->
                OutlinedButton(enabled = valid, onClick = {
                    startExport(ExportTask(listOf(primary(), Clip(other)), emptyList(), exportOptions))
                }) { Text("Merge and export") }
            }
        }
        latest?.let { file ->
            TextButton(onClick = { pendingCopy = file; exportVideo.launch(file.name) }) { Text("Save exported MP4") }
        }
    }

    @Composable
    private fun VideoPlayer(uri: Uri) {
        val player = remember(uri) {
            ExoPlayer.Builder(this).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        key(player) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().height(250.dp))
        }
    }

    @Composable
    private fun MaskCanvas(bitmap: Bitmap, enabled: Boolean) {
        var anchor by remember { mutableStateOf<Offset?>(null) }
        var cursor by remember { mutableStateOf<Offset?>(null) }
        Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)) {
            Image(bitmap.asImageBitmap(), "Video frame", Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.matchParentSize().pointerInput(enabled) {
                if (enabled) detectDragGestures(
                    onDragStart = { anchor = it; cursor = it },
                    onDragEnd = {
                        val a = anchor
                        val b = cursor
                        if (a != null && b != null && regions.size < HudProfile.MAX_REGIONS) {
                            fun x(v: Float) = (v / size.width).coerceIn(0f, 1f)
                            fun y(v: Float) = (v / size.height).coerceIn(0f, 1f)
                            runCatching { Region(x(min(a.x,b.x)), y(min(a.y,b.y)), x(max(a.x,b.x)), y(max(a.y,b.y))) }
                                .onSuccess { regions = regions + it }
                        }
                        anchor = null
                        cursor = null
                    },
                    onDragCancel = { anchor = null; cursor = null }
                ) { change, _ -> change.consume(); cursor = change.position }
            }) {
                regions.forEach { rect ->
                    drawRect(Color.Cyan.copy(alpha = .42f), Offset(rect.left * size.width, rect.top * size.height),
                        Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height))
                }
                val a = anchor
                val b = cursor
                if (a != null && b != null) drawRect(Color.Magenta.copy(alpha = .4f),
                    Offset(min(a.x,b.x), min(a.y,b.y)), Size(kotlin.math.abs(b.x-a.x), kotlin.math.abs(b.y-a.y)))
            }
        }
        Text("${regions.size} of ${HudProfile.MAX_REGIONS} regions")
    }
}
