package com.ghostcapture.app.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ghostcapture.app.MainActivity
import com.ghostcapture.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records the screen with MediaProjection +
 * MediaRecorder. The app never draws touch indicators, and the floating
 * controls do not exist while recording — Stop / Pause / Resume live only
 * in this notification, so no recorder UI leaks into the video.
 *
 * Audio: microphone only (MediaRecorder). Internal/device audio is NOT in
 * this version — it needs a MediaCodec + AudioPlaybackCapture pipeline
 * (Phase 3). The app never claims to record internal audio.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.ghostcapture.app.action.START"
        const val ACTION_STOP = "com.ghostcapture.app.action.STOP"
        const val ACTION_PAUSE = "com.ghostcapture.app.action.PAUSE"
        const val ACTION_RESUME = "com.ghostcapture.app.action.RESUME"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO_MIC = "audio_mic"
        const val EXTRA_BITRATE = "bitrate"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "ghostcapture_recording"
        private const val TAG = "RecorderService"

        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            width: Int,
            height: Int,
            density: Int,
            fps: Int,
            audioMic: Boolean,
            bitrate: Int
        ): Intent {
            return Intent(context, RecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DENSITY, density)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_AUDIO_MIC, audioMic)
                putExtra(EXTRA_BITRATE, bitrate)
            }
        }

        fun actionIntent(context: Context, action: String): Intent {
            return Intent(context, RecorderService::class.java).apply {
                this.action = action
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputFileName: String? = null
    private var startTimeMs: Long = 0L
    private var pausedTotalMs: Long = 0L
    private var pauseStartMs: Long = 0L
    private var paused: Boolean = false
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = getResultData(intent)
        if (resultCode == 0 || resultData == null) {
            RecorderState.error.value = "Screen capture permission was not granted."
            stopSelf()
            return
        }
        val width = intent.getIntExtra(EXTRA_WIDTH, 1920)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        val density = intent.getIntExtra(EXTRA_DENSITY, 320)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val audioMic = intent.getBooleanExtra(EXTRA_AUDIO_MIC, false)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 16_000_000)

        // Foreground first: Android 14 requires the mediaProjection
        // foreground service to be running before getMediaProjection().
        startForeground(
            NOTIF_ID,
            buildNotification("Starting recording…", paused = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        try {
            outputFileName =
                "GhostCapture_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GhostCapture")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("Could not create video file.")
            outputUri = uri
            outputPfd = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open video file.")

            val recorder = createMediaRecorder()
            mediaRecorder = recorder
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (audioMic) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audioMic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoEncodingBitRate(bitrate)
            recorder.setOutputFile(outputPfd!!.fileDescriptor)
            recorder.prepare()

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection = projection
            // Android 14+ REQUIRES a callback registered BEFORE
            // createVirtualDisplay(), otherwise it throws:
            // "Must register a callback before starting capture, to manage
            // resources in response to MediaProjection states."
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // The system stopped the projection (e.g. the user
                    // revoked capture). Stop gracefully if still recording.
                    if (RecorderState.isRecording.value) {
                        handleStop()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            virtualDisplay = projection.createVirtualDisplay(
                "GhostCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )
            recorder.start()

            startTimeMs = System.currentTimeMillis()
            pausedTotalMs = 0L
            paused = false
            RecorderState.currentFileName.value = outputFileName
            RecorderState.isRecording.value = true
            RecorderState.isPaused.value = false
            RecorderState.elapsedMs.value = 0L
            startTimer()
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            RecorderState.error.value = "Recording failed to start: ${e.message}"
            cleanupAfterFailure()
            stopSelf()
        }
    }

    private fun handlePause() {
        if (mediaRecorder == null || paused) return
        try {
            mediaRecorder?.pause()
            paused = true
            pauseStartMs = System.currentTimeMillis()
            RecorderState.isPaused.value = true
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed", e)
        }
    }

    private fun handleResume() {
        if (mediaRecorder == null || !paused) return
        try {
            mediaRecorder?.resume()
            pausedTotalMs += System.currentTimeMillis() - pauseStartMs
            paused = false
            RecorderState.isPaused.value = false
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Resume failed", e)
        }
    }

    private fun handleStop() {
        stopRecording(successExpected = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Unregister the projection callback and stop the projection. */
    private fun releaseProjection() {
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            // Nothing to do — callback is being released anyway.
        }
        projectionCallback = null
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            // Nothing to do — projection is being released anyway.
        }
        mediaProjection = null
    }

    private fun stopRecording(successExpected: Boolean) {
        timerJob?.cancel()
        timerJob = null
        var ok = successExpected
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.stop failed", e)
            ok = false
        }
        try {
            mediaRecorder?.reset()
        } catch (e: Exception) {
            // Nothing to do — recorder is being released anyway.
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        releaseProjection()
        try {
            outputPfd?.close()
        } catch (e: Exception) {
            // Nothing to do — file descriptor is being released anyway.
        }
        outputPfd = null

        val uri = outputUri
        outputUri = null
        if (uri != null) {
            if (ok) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    contentResolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to publish recording", e)
                    ok = false
                }
                if (ok) {
                    RecorderState.lastSavedUri.value = uri
                }
            }
            if (!ok) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    // Nothing to do — the failed file may already be gone.
                }
                RecorderState.error.value = "Recording could not be saved."
            }
        }
        RecorderState.isRecording.value = false
        RecorderState.isPaused.value = false
        RecorderState.elapsedMs.value = 0L
    }

    private fun cleanupAfterFailure() {
        try {
            mediaRecorder?.reset()
        } catch (e: Exception) {
            // Nothing to do — recorder is being released anyway.
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        releaseProjection()
        try {
            outputPfd?.close()
        } catch (e: Exception) {
            // Nothing to do — file descriptor is being released anyway.
        }
        outputPfd = null
        val uri = outputUri
        outputUri = null
        if (uri != null) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Nothing to do — the failed file may already be gone.
            }
        }
        RecorderState.isRecording.value = false
        RecorderState.isPaused.value = false
    }

    private fun releaseAll() {
        timerJob?.cancel()
        timerJob = null
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Nothing to do — service is being destroyed.
        }
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        releaseProjection()
        try {
            outputPfd?.close()
        } catch (e: Exception) {
            // Nothing to do — service is being destroyed.
        }
        outputPfd = null
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                if (!paused) {
                    val elapsed =
                        System.currentTimeMillis() - startTimeMs - pausedTotalMs
                    RecorderState.elapsedMs.value = elapsed
                    updateNotification()
                }
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "GhostCapture screen recording controls"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, paused: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            actionIntent(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumeIntent = PendingIntent.getService(
            this,
            2,
            actionIntent(this, if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GhostCapture AI")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (RecorderState.isRecording.value) {
            builder.addAction(
                0,
                if (paused) "Resume" else "Pause",
                pauseResumeIntent
            )
            builder.addAction(0, "Stop", stopIntent)
        }
        return builder.build()
    }

    private fun updateNotification() {
        val text = if (paused) {
            "Paused — " + formatElapsed(RecorderState.elapsedMs.value)
        } else {
            "Recording — " + formatElapsed(RecorderState.elapsedMs.value)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, paused))
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun getResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
    }

    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }
}
