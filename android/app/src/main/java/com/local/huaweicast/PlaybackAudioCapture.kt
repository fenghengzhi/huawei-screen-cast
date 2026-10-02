package com.local.huaweicast

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.concurrent.thread

/** Captures app playback only, never microphone input. */
class PlaybackAudioCapture(
    private val onFrame: (ByteArray, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recorder: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // Permission is requested before MediaProjection consent.
    fun start(projection: MediaProjection) {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val minBuffer = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "设备不支持 48 kHz 立体声音频采集" }
            val record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 16384)).build()
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "系统声音采集初始化失败" }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统声音录制未启动" }
            running = true
            worker = thread(name = "playback-aac", isDaemon = true) {
                try { capture(record, encoder) }
                catch (error: Exception) { if (running) onError("系统声音录制失败：${error.message}") }
            }
            Log.i("HuaweiCastAudio", "系统音频采集已启动 AAC-LC 48000 Hz stereo 128 kbps")
        } catch (error: Exception) {
            stop()
            throw IllegalStateException("无法录制系统声音：${error.message}", error)
        }
    }

    private fun capture(record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var anchorUs: Long? = null
        var lastPts = -1L
        while (running) {
            val input = encoder.dequeueInputBuffer(10000)
            if (input >= 0) {
                val buffer = checkNotNull(encoder.getInputBuffer(input))
                buffer.clear()
                val size = record.read(pcm, 0, minOf(pcm.size, buffer.remaining()) / 4 * 4, AudioRecord.READ_BLOCKING)
                if (!running) break
                check(size > 0 && size % 4 == 0) { "AudioRecord.read=$size" }
                // AudioTimestamp and EGL video timestamps both use CLOCK_MONOTONIC.
                val hasTimestamp = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                if (anchorUs == null) anchorUs = System.nanoTime() / 1000 - (framesRead + size / 4) * 1000000 / 48000
                val pts = if (hasTimestamp) timestamp.nanoTime / 1000 + (framesRead - timestamp.framePosition) * 1000000 / 48000
                    else anchorUs!! + framesRead * 1000000 / 48000
                val monotonicPts = maxOf(pts, lastPts + 1)
                buffer.put(pcm, 0, size)
                encoder.queueInputBuffer(input, 0, size, monotonicPts, 0)
                framesRead += size / 4
                lastPts = monotonicPts
            }
            while (running) {
                val output = encoder.dequeueOutputBuffer(info, 0)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (output < 0) break
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = checkNotNull(encoder.getOutputBuffer(output))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val payload = ByteArray(info.size); buffer.get(payload)
                        onFrame(AacAdts.wrap(payload), info.presentationTimeUs)
                    }
                } finally { encoder.releaseOutputBuffer(output, false) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.stop() }
        worker?.join()
        worker = null
        runCatching { recorder?.release() }; recorder = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }; codec = null
    }
}
