package org.ghostcapture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

internal data class CaptureSize(val width: Int, val height: Int)
internal data class CaptureState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val message: String = "",
    val elapsedMillis: Long = 0,
)

class CaptureService : Service() {
    private var recorder: CodecRecording? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var pending: File? = null
    private var started = false
    private var startedAt = 0L
    private var pausedAt: Long? = null
    private var pausedMillis = 0L
    private var finishing = false
    private val handler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            if (!state.value.active) return
            val bytes = StatFs(filesDir.path).availableBytes
            if (bytes < 64L * 1024 * 1024) {
                finish("Recording stopped to protect remaining storage.")
                return
            }
            if (getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
                finish("Recording stopped because Android reported critical thermal conditions.")
                return
            }
            state.value = state.value.copy(elapsedMillis = elapsedMillis())
            updateNotification(bytes < 512L * 1024 * 1024)
            handler.postDelayed(this, 1000)
        }
    }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { finish() }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> finish()
            "pause" -> changePause(true)
            "resume" -> changePause(false)
            "start" -> if (!started) {
                try {
                    begin(intent)
                } catch (error: Exception) {
                    finish(error.message ?: "Could not start recording.")
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun begin(intent: Intent) {
        val audio = AudioSource.entries.getOrElse(intent.getIntExtra("audio", 0)) { AudioSource.SILENT }
        val width = intent.getIntExtra("width", 0)
        val height = intent.getIntExtra("height", 0)
        val fps = intent.getIntExtra("fps", 30)
        val mime = intent.getStringExtra("mime") ?: MediaFormat.MIMETYPE_VIDEO_AVC
        val bitrate = intent.getIntExtra("bitrate", width * height * fps / 7)
        val token = requireNotNull(intent.getParcelableExtra<Intent>("token")) { "Capture consent is missing." }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording controls", NotificationManager.IMPORTANCE_LOW)
        )
        val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (audio.mic && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(1, notification(), serviceType)
        check(StatFs(filesDir.path).availableBytes >= 128L * 1024 * 1024) { "Free up storage before recording." }
        check(supported(CaptureSize(width, height), fps, mime)) { "The selected hardware encoder configuration is unavailable." }
        val file = File(recordings(filesDir), "${UUID.randomUUID()}.recording")
        pending = file
        val capture = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(Activity.RESULT_OK, token)
        projection = capture
        capture.registerCallback(callback, handler)
        val writer = CodecRecording.open(capture, file, CaptureSize(width, height), fps, mime, bitrate, audio)
        recorder = writer
        display = capture.createVirtualDisplay(
            "GhostCapture", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, writer.surface, null, handler
        )
        checkNotNull(display) { "Android could not create the capture display." }
        writer.start { failure -> handler.post {
            if (!finishing) releaseProjection()
            complete(failure, stopReason)
        } }
        started = true
        startedAt = SystemClock.elapsedRealtime()
        state.value = CaptureState(active = true)
        monitor.run()
    }

    private fun changePause(pause: Boolean) {
        if (!started || state.value.paused == pause) return
        try {
            recorder?.pause(pause)
            val now = SystemClock.elapsedRealtime()
            if (pause) pausedAt = now
            else {
                pausedAt?.let { pausedMillis += now - it }
                pausedAt = null
            }
            state.value = state.value.copy(paused = pause, elapsedMillis = elapsedMillis())
            updateNotification()
        } catch (error: RuntimeException) {
            state.value = state.value.copy(message = error.message ?: "The encoder rejected the pause change.")
        }
    }

    private var stopReason = ""

    private fun releaseProjection() {
        finishing = true
        handler.removeCallbacks(monitor)
        runCatching { display?.release() }
        runCatching {
            projection?.unregisterCallback(callback)
            projection?.stop()
        }
    }

    private fun finish(reason: String = "") {
        if (finishing) return
        stopReason = reason
        val writer = recorder
        writer?.stop()
        releaseProjection()
        if (!started) complete(null, reason)
    }

    private fun complete(error: Exception?, reason: String) {
        var message = error?.message ?: reason
        val finalized = started && error == null
        pending?.let { file ->
            if (finalized) {
                val destination = File(file.parentFile, "${file.nameWithoutExtension}.mp4")
                if (file.renameTo(destination)) {
                    if (message.isEmpty()) message = "Recording saved."
                } else {
                    message = "Recording finalized. Its recovery file is still in the gallery."
                }
            } else if (file.length() == 0L) {
                file.delete()
            } else if (message.isEmpty()) {
                message = "The recording may be incomplete. Its recovery file is still in the gallery."
            }
        }
        state.value = CaptureState(message = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun updateNotification(lowStorage: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(1, notification(lowStorage))
    }

    private fun elapsedMillis(): Long {
        if (startedAt == 0L) return 0
        return ((pausedAt ?: SystemClock.elapsedRealtime()) - startedAt - pausedMillis).coerceAtLeast(0)
    }

    private fun notification(lowStorage: Boolean = false): Notification {
        val paused = state.value.paused
        fun action(name: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CaptureService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "GhostCapture paused" else "GhostCapture recording")
            .setContentText(if (lowStorage) "Storage is running low" else "Screen capture")
            .setOngoing(true)
            .setWhen(System.currentTimeMillis() - elapsedMillis())
            .setUsesChronometer(startedAt > 0 && !paused)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause",
                action(if (paused) "resume" else "pause", 1)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action("stop", 2)).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "capture"
        internal val state = MutableStateFlow(CaptureState())
        internal fun recordings(parent: File) = File(parent, "recordings").apply { mkdirs() }

        internal fun supported(size: CaptureSize, fps: Int, mime: String) =
            CodecRecording.encoder(size, fps, mime) != null
    }
}
