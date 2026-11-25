package org.ghostcapture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

internal enum class AudioSource(val label: String, val mic: Boolean, val playback: Boolean) {
    SILENT("Silent", false, false),
    INTERNAL("Game audio", false, true),
    MICROPHONE("Microphone", true, false),
    MIXED("Game audio and microphone", true, true)
}

internal class CodecRecording private constructor(
    private val video: MediaCodec,
    private val audio: MediaCodec?,
    private val internalAudio: AudioRecord?,
    private val microphone: AudioRecord?,
    private val muxer: MediaMuxer,
    val surface: Surface,
) {
    private data class Sample(val stream: Int, val bytes: ByteArray, val time: Long, val flags: Int)

    private var running = false
    @Volatile private var stopping = false
    private val timeline = CaptureTimeline(nowUs())
    @Volatile private var requestKeyFrame = false
    private var lastVideoSegment = -1
    private val codecTracks = intArrayOf(-1, -1)
    private val lastTimes = longArrayOf(-1, -1)
    private val waiting = ArrayDeque<Sample>()
    private var waitingBytes = 0L
    private var muxerStarted = false
    private var videoSamples = 0
    private var inputTimeOffset: Long? = null
    private var nextAudioTime: Long? = null
    private var audioEnded = false
    private var videoEnded = false
    private val rawBuffer = ShortArray(1024)
    private val mixBuffer = ShortArray(1024)

    fun start(finished: (Exception?) -> Unit) {
        internalAudio?.startRecording()
        microphone?.startRecording()
        for (source in listOfNotNull(internalAudio, microphone)) {
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android could not start the selected audio source." }
        }
        running = true
        Thread({
            var failure: Exception? = null
            try {
                writeLoop()
            } catch (error: Exception) {
                failure = error
            } finally {
                val final = release()
                finished(failure ?: final)
            }
        }, "GhostCapture encoder").start()
    }

    fun pause(value: Boolean) {
        timeline.setPaused(value, nowUs())
        if (!value) requestKeyFrame = true
    }

    fun stop() {
        if (running) stopping = true
        else release()
    }

    private fun writeLoop() {
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var stoppingSince = 0L
        var audioSignalled = false
        while (!videoEnded || audio != null && !audioEnded) {
            if (stopping && stoppingSince == 0L) {
                video.signalEndOfInputStream()
                stoppingSince = nowUs()
            }
            if (stoppingSince > 0 && nowUs() - stoppingSince > 5_000_000) {
                error("The encoder did not finish draining before the stop deadline.")
            }
            if (requestKeyFrame && !stopping) {
                requestKeyFrame = false
                runCatching { video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            }
            var processed = drain(video, 0, videoInfo)
            audio?.let {
                if (!stopping) {
                    processed = feedAudio(it) || processed
                } else if (!audioSignalled) {
                    val index = it.dequeueInputBuffer(0)
                    if (index >= 0) {
                        it.queueInputBuffer(index, 0, 0, max(0, lastTimes[1] + 1), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioSignalled = true
                    }
                }
                processed = drain(it, 1, audioInfo) || processed
            }
            if (!processed) Thread.sleep(3)
        }
        check(muxerStarted && videoSamples > 0) { "The recording ended before the first video frame." }
    }

    private fun feedAudio(codec: MediaCodec): Boolean {
        val primary = internalAudio ?: requireNotNull(microphone)
        val count = primary.read(rawBuffer, 0, rawBuffer.size, AudioRecord.READ_NON_BLOCKING)
        check(count >= 0) { "Android audio recording failed ($count)." }
        if (count == 0) return false
        if (internalAudio != null && microphone != null) {
            val micCount = microphone.read(mixBuffer, 0, count, AudioRecord.READ_NON_BLOCKING)
            check(micCount >= 0) { "Android microphone capture failed ($micCount)." }
            for (i in 0 until micCount) rawBuffer[i] = ((rawBuffer[i].toInt() + mixBuffer[i].toInt()) / 2).toShort()
        }
        val duration = count * 1_000_000L / SAMPLE_RATE
        val now = nowUs()
        val timestamp = timeline.timestamp(now - duration)
        if (timestamp == null) {
            nextAudioTime = null
            return true
        }
        val candidate = nextAudioTime?.takeIf { abs(timestamp - it) < 150_000L } ?: timestamp
        nextAudioTime = candidate + duration
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return true
        val input = requireNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
        input.clear()
        input.asShortBuffer().put(rawBuffer, 0, count)
        codec.queueInputBuffer(index, 0, count * 2, candidate, 0)
        return true
    }

    private fun drain(codec: MediaCodec, stream: Int, info: MediaCodec.BufferInfo): Boolean {
        val index = codec.dequeueOutputBuffer(info, 0)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            codecTracks[stream] = muxer.addTrack(codec.outputFormat)
            if (!muxerStarted && codecTracks[0] >= 0 && (audio == null || codecTracks[1] >= 0)) {
                muxer.start()
                muxerStarted = true
                while (waiting.isNotEmpty()) {
                    val sample = waiting.removeFirst()
                    write(sample.stream, ByteBuffer.wrap(sample.bytes), sample.time, sample.flags)
                }
                waitingBytes = 0
            }
            return true
        }
        if (index < 0) return false
        try {
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (eos) {
                if (stream == 0) videoEnded = true else audioEnded = true
            }
            if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return true
            val time = if (stream == 0) {
                val raw = info.presentationTimeUs
                val offset = inputTimeOffset ?: (nowUs() - raw)
                    .takeIf { abs(it) > 10_000_000 }?.also { inputTimeOffset = it } ?: 0L
                val stamp = timeline.stamp(raw + offset) ?: return true
                if (stamp.segment != lastVideoSegment) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return true
                    lastVideoSegment = stamp.segment
                }
                stamp.timeUs
            } else max(0, info.presentationTimeUs)
            if (time <= lastTimes[stream]) return true
            lastTimes[stream] = time
            val buffer = requireNotNull(codec.getOutputBuffer(index))
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val flags = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
            if (muxerStarted) {
                write(stream, buffer.slice(), time, flags)
            } else {
                waitingBytes += info.size
                check(waitingBytes <= 16L * 1024 * 1024) { "An audio or video track failed to initialize." }
                val copy = ByteArray(info.size)
                buffer.get(copy)
                waiting.add(Sample(stream, copy, time, flags))
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
        return true
    }

    private fun write(stream: Int, buffer: ByteBuffer, time: Long, flags: Int) {
        val info = MediaCodec.BufferInfo().apply { set(0, buffer.remaining(), time, flags) }
        muxer.writeSampleData(codecTracks[stream], buffer, info)
        if (stream == 0) videoSamples++
    }

    private fun release(): Exception? {
        for (source in listOfNotNull(internalAudio, microphone)) {
            runCatching { source.stop() }
            runCatching { source.release() }
        }
        runCatching { surface.release() }
        dispose(video)
        dispose(audio)
        val error = if (muxerStarted) runCatching { muxer.stop() }.exceptionOrNull() as? Exception else null
        runCatching { muxer.release() }
        return error
    }

    companion object {
        private const val SAMPLE_RATE = 48000
        private fun nowUs() = System.nanoTime() / 1000L
        private fun dispose(codec: MediaCodec?) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }

        fun encoder(size: CaptureSize, fps: Int, mime: String, bitrate: Int? = null) =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, true) } &&
                    runCatching {
                        val capabilities = info.getCapabilitiesForType(mime)
                        val video = capabilities.videoCapabilities
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in capabilities.colorFormats &&
                            video.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) &&
                            (bitrate == null || video.bitrateRange.contains(bitrate))
                    }.getOrDefault(false)
            }

        @SuppressLint("MissingPermission")
        fun open(projection: MediaProjection, file: File, size: CaptureSize, fps: Int,
                 mime: String, bitrate: Int, source: AudioSource): CodecRecording {
            var video: MediaCodec? = null
            var audio: MediaCodec? = null
            var game: AudioRecord? = null
            var microphone: AudioRecord? = null
            var surface: Surface? = null
            var muxer: MediaMuxer? = null
            try {
                val encoder = requireNotNull(encoder(size, fps, mime, bitrate)) {
                    "This hardware encoder does not support the selected bitrate, size, and frame rate."
                }
                val videoFormat = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                }
                val videoCodec = MediaCodec.createByCodecName(encoder.name).also { video = it }
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val videoSurface = videoCodec.createInputSurface().also { surface = it }
                videoCodec.start()
                if (source != AudioSource.SILENT) {
                    val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                    val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minSize > 0) { "The selected audio format is unsupported." }
                    fun builder() = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(max(minSize * 2, 8192))
                    if (source.playback) {
                        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).build()
                        game = builder().setAudioPlaybackCaptureConfig(capture).build()
                    }
                    if (source.mic) microphone = builder().setAudioSource(MediaRecorder.AudioSource.MIC).build()
                    val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048)
                    }
                    val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { audio = it }
                    audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioCodec.start()
                }
                val output = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
                return CodecRecording(videoCodec, audio, game, microphone, output, videoSurface)
            } catch (error: Exception) {
                runCatching { game?.release() }
                runCatching { microphone?.release() }
                runCatching { surface?.release() }
                dispose(video)
                dispose(audio)
                runCatching { muxer?.release() }
                throw error
            }
        }
    }
}
