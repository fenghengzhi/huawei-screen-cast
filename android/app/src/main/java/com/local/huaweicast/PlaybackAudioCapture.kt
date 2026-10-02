package com.local.huaweicast

import android.annotation.SuppressLint
import android.media.*
import android.media.projection.MediaProjection
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captures app playback only, never microphone input. */
class PlaybackAudioCapture(
    private val onFrame: (ByteArray, Long) -> Unit,
    private val onError: (String) -> Unit,
    private val sampleRate: Int = 48000,
    private val aacProfile: Int = MediaCodecInfo.CodecProfileLevel.AACObjectLC,
    private val rawOutput: Boolean = false,
    private val onFormat: ((ByteArray) -> Unit)? = null,
    private val useNativeEld: Boolean = false
) {
    private class Session {
        @Volatile var active = true
        @Volatile var recorder: AudioRecord? = null
        @Volatile var codec: MediaCodec? = null
        @Volatile var worker: Thread? = null
        val finished = CountDownLatch(1)
    }

    private class CaptureCancelled : Exception()

    private val lifecycle = Any()
    private var session: Session? = null
    private var closed = false

    init {
        require(sampleRate == 44100 || sampleRate == 48000) { "Unsupported AAC sample rate" }
        require(aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectLC ||
            aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectELD) { "Unsupported AAC profile" }
        require(rawOutput || (sampleRate == 48000 && aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectLC)) {
            "ADTS output requires AAC-LC at 48000 Hz"
        }
        require(!useNativeEld || (rawOutput && aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectELD)) {
            "Native AAC-ELD requires raw AAC-ELD output"
        }
    }

    @SuppressLint("MissingPermission") // Permission is requested before MediaProjection consent.
    fun start(projection: MediaProjection) {
        val current = synchronized(lifecycle) {
            if (closed) return
            check(session == null) { "系统声音采集已启动或正在停止" }
            Session().also { session = it }
        }
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "设备不支持 $sampleRate Hz 立体声音频采集" }
            ensureActive(current)
            val record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 16384)).build()
            current.recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "系统声音采集初始化失败" }
            ensureActive(current)
            if (useNativeEld) {
                launchWorker(current) { captureNative(current, record) }
                return
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, samplesPerFrame() * 4)
            }
            val encoder = createEncoder(format)
            current.codec = encoder
            ensureActive(current)
            encoder.start()
            ensureActive(current)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统声音录制未启动" }
            val encoderName = encoder.name
            Log.i("HuaweiCastAudio", "系统音频采集已启动 codec=$encoderName profile=$aacProfile $sampleRate Hz stereo 128 kbps raw=$rawOutput")
            launchWorker(current) { capture(current, record, encoder) }
        } catch (error: Exception) {
            val cancelled = !current.active || error is CaptureCancelled
            release(current)
            if (!cancelled) throw IllegalStateException("无法录制系统声音：${error.message}", error)
        }
    }

    private fun launchWorker(current: Session, capture: () -> Unit) {
        synchronized(lifecycle) {
            ensureActive(current)
            val worker = Thread({
                var failure: Exception? = null
                try { capture() }
                catch (error: Exception) { if (current.active) failure = error }
                catch (error: LinkageError) {
                    if (current.active) failure = IllegalStateException("AAC-ELD 本地编码器无法加载", error)
                }
                finally { release(current) }
                failure?.let { onError("系统声音录制失败：${it.message}") }
            }, "playback-aac").apply { isDaemon = true }
            current.worker = worker
            worker.start()
        }
    }

    private fun samplesPerFrame() = if (aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectELD) 512 else 1024

    private fun createEncoder(format: MediaFormat): MediaCodec {
        if (aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectLC) {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return encoder
            } catch (error: Exception) {
                runCatching { encoder.release() }
                throw error
            }
        }
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } &&
                runCatching {
                    val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    capabilities.profileLevels.any { it.profile == aacProfile } && capabilities.isFormatSupported(format)
                }.getOrDefault(false)
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            var encoder: MediaCodec? = null
            try {
                encoder = MediaCodec.createByCodecName(candidate.name)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return encoder
            } catch (error: Exception) {
                runCatching { encoder?.release() }
                lastError = error
            }
        }
        throw IllegalStateException("设备没有可用的 AAC-ELD 系统声音编码器", lastError)
    }

    private fun capture(current: Session, record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(samplesPerFrame() * 4)
        val info = MediaCodec.BufferInfo()
        val timestamp = AudioTimestamp()
        val config = AudioCodecConfigLatch()
        val tracksConfig = rawOutput || onFormat != null
        fun emitConfig(data: ByteArray) {
            config.accept(data)?.let { onFormat?.invoke(it) }
        }
        fun readFormat() {
            val format = encoder.outputFormat
            check(!format.containsKey(MediaFormat.KEY_SAMPLE_RATE) || format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == sampleRate) {
                "AAC 编码器改变了采样率"
            }
            check(!format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) {
                "AAC 编码器改变了声道数"
            }
            format.getByteBuffer("csd-0")?.duplicate()?.let { buffer ->
                emitConfig(ByteArray(buffer.remaining()).also { buffer.get(it) })
            }
        }
        val clock = PlaybackAudioClock(sampleRate)
        while (current.active) {
            val input = encoder.dequeueInputBuffer(10000)
            if (input >= 0) {
                val buffer = checkNotNull(encoder.getInputBuffer(input))
                buffer.clear()
                check(buffer.remaining() >= pcm.size) { "AAC 编码器输入缓冲区过小" }
                val size = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (!current.active) break
                check(size > 0 && size % 4 == 0) { "AudioRecord.read=$size" }
                val monotonicPts = recordReadTime(record, timestamp, clock, size / 4)
                buffer.put(pcm, 0, size)
                encoder.queueInputBuffer(input, 0, size, monotonicPts, 0)
            }
            while (current.active) {
                val output = encoder.dequeueOutputBuffer(info, 0)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (tracksConfig) readFormat()
                    continue
                }
                if (output < 0) break
                try {
                    if (info.size > 0) {
                        val buffer = checkNotNull(encoder.getOutputBuffer(output))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val payload = ByteArray(info.size); buffer.get(payload)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (tracksConfig) emitConfig(payload)
                        } else {
                            if (rawOutput && !config.hasConfig()) readFormat()
                            check(!rawOutput || config.hasConfig()) { "AAC 编码器未提供音频配置" }
                            onFrame(if (rawOutput) payload else AacAdts.wrap(payload), info.presentationTimeUs)
                        }
                    }
                } finally { encoder.releaseOutputBuffer(output, false) }
            }
        }
    }

    private fun captureNative(current: Session, record: AudioRecord) {
        ensureActive(current)
        // Native initialization, encoding and close all stay on this worker, never on stop().
        val encoder = NativeAacEldEncoder(sampleRate)
        try {
            val frameSamples = encoder.samplesPerFrame()
            val delaySamples = encoder.delaySamples()
            check(frameSamples == 480 && delaySamples >= 0) { "AAC-ELD 编码器帧长或延迟无效" }
            val config = AudioCodecConfigLatch().accept(encoder.configuration())!!
            ensureActive(current)
            onFormat?.invoke(config)
            ensureActive(current)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统声音录制未启动" }
            Log.i("HuaweiCastAudio", "系统音频采集已启动 codec=FDK-AAC profile=$aacProfile $sampleRate Hz stereo raw=true samples=$frameSamples delay=$delaySamples")
            val pcm = ByteArray(frameSamples * 4)
            val timestamp = AudioTimestamp()
            val clock = PlaybackAudioClock(sampleRate)
            var filled = 0
            while (current.active) {
                val size = record.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                if (!current.active) break
                check(size > 0 && size % 4 == 0) { "AudioRecord.read=$size" }
                recordReadTime(record, timestamp, clock, size / 4)
                filled += size
                if (filled < pcm.size) continue
                val packet = encoder.encode(pcm, filled)
                filled = 0
                if (packet != null && packet.isNotEmpty() && current.active) {
                    onFrame(packet, clock.nextPacketTime(frameSamples, delaySamples))
                }
            }
        } finally { encoder.close() }
    }

    private fun recordReadTime(record: AudioRecord, timestamp: AudioTimestamp, clock: PlaybackAudioClock, samples: Int): Long {
        // AudioTimestamp and EGL video timestamps both use CLOCK_MONOTONIC.
        val hasTimestamp = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        return clock.read(samples, timestamp.nanoTime.takeIf { hasTimestamp }, timestamp.framePosition, System.nanoTime())
    }

    fun stop() {
        val current = synchronized(lifecycle) {
            closed = true
            session?.also { it.active = false }
        } ?: return
        runCatching { current.recorder?.stop() }
        if (current.worker !== Thread.currentThread()) {
            try {
                if (!current.finished.await(1500, TimeUnit.MILLISECONDS)) {
                    Log.w("HuaweiCastAudio", "音频线程停止超时，将在线程退出后释放资源")
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun ensureActive(current: Session) {
        if (!current.active) throw CaptureCancelled()
    }

    // The start thread owns cleanup until the worker is launched; afterwards only the worker releases.
    private fun release(current: Session) {
        current.active = false
        runCatching { current.recorder?.stop() }
        runCatching { current.recorder?.release() }; current.recorder = null
        runCatching { current.codec?.stop() }
        runCatching { current.codec?.release() }; current.codec = null
        synchronized(lifecycle) { if (session === current) session = null }
        current.finished.countDown()
    }
}

/** Maps recorded samples and encoder output samples onto the same monotonic video clock. */
internal class PlaybackAudioClock(private val sampleRate: Int) {
    private var framesRead = 0L
    private var outputSamples = 0L
    private var anchorUs: Long? = null
    private var referenceUs = 0L
    private var referenceFrame = 0L
    private var lastReadPts = -1L
    private var lastPacketPts = -1L
    private var packetAnchorUs: Long? = null
    private var hardwareAnchorUs: Long? = null
    private var lastHardwareNs = -1L
    private var lastHardwareFrame = -1L

    init { require(sampleRate > 0) }

    fun read(samples: Int, captureNanoTime: Long?, captureFramePosition: Long, nowNanoTime: Long): Long {
        require(samples > 0)
        if (anchorUs == null) anchorUs = nowNanoTime / 1000 - (framesRead + samples) * 1000000 / sampleRate
        referenceUs = captureNanoTime?.div(1000) ?: anchorUs!!
        referenceFrame = if (captureNanoTime != null) captureFramePosition else 0L
        if (captureNanoTime != null && captureFramePosition >= 0 &&
            captureNanoTime > lastHardwareNs && captureFramePosition > lastHardwareFrame) {
            hardwareAnchorUs = timeAt(0)
            lastHardwareNs = captureNanoTime
            lastHardwareFrame = captureFramePosition
        }
        val pts = maxOf(timeAt(framesRead), lastReadPts + 1)
        framesRead += samples
        lastReadPts = pts
        return pts
    }

    fun nextPacketTime(samples: Int, delaySamples: Int): Long {
        check(anchorUs != null) { "No captured audio samples" }
        require(samples > 0 && delaySamples >= 0)
        val previousAnchor = packetAnchorUs
        if (previousAnchor == null) {
            packetAnchorUs = hardwareAnchorUs ?: anchorUs!!
        } else {
            // An AU is never shortened to lastPts+1 by a jittery or temporarily absent hardware timestamp.
            // Follow capture-clock drift at at most 1000 ppm while keeping the sample counter continuous.
            val maxSlewUs = samples * 1000L / sampleRate
            val target = hardwareAnchorUs ?: previousAnchor
            packetAnchorUs = previousAnchor + (target - previousAnchor).coerceIn(-maxSlewUs, maxSlewUs)
        }
        // Encoder priming belongs before the first PCM sample, even when initial encode calls produce no AU.
        val pts = packetAnchorUs!! + (outputSamples - delaySamples) * 1000000 / sampleRate
        check(pts > lastPacketPts) { "Encoded audio sample clock did not advance" }
        outputSamples += samples
        lastPacketPts = pts
        return pts
    }

    private fun timeAt(position: Long) = referenceUs + (position - referenceFrame) * 1000000 / sampleRate
}

/** A live RAOP session cannot silently switch the configuration announced in its SDP. */
internal class AudioCodecConfigLatch {
    private var accepted: ByteArray? = null

    fun hasConfig() = accepted != null

    fun accept(config: ByteArray): ByteArray? {
        require(config.isNotEmpty() && config.size <= 64) { "Invalid AAC codec configuration" }
        val previous = accepted
        if (previous != null) {
            check(previous.contentEquals(config)) { "AAC 编码配置在投屏期间发生变化" }
            return null
        }
        accepted = config.copyOf()
        return config.copyOf()
    }
}
