// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.screen

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import com.airsonic.sender.streaming.TsMuxer
import com.local.huaweicast.VideoCodec
import com.local.huaweicast.VideoParameterSets
import kotlin.concurrent.thread

/**
 * 屏幕镜像采集编码器：MediaProjection → VirtualDisplay → MediaCodec(H.264/H.265 surface 输入)
 * → Annex-B → TsMuxer → 188B TS 包经 [onTsPacket] 扇出（交给 HTTP 流服务器）。
 *
 * 编码参数按 DLNA 实时流调优：CBR 恒定码率（压突发防拥塞）、1s GOP、码率可调。
 * 丢包自愈采用 drop-until-IDR：一旦下行拥塞丢包，停止推送直到下个关键帧干净续流
 * （把损坏的 P 帧喂给播放器会让其解码器冻屏）。
 */
class ScreenMirrorCaster(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val dpi: Int = 320,
    private val bitRate: Int = 10_000_000,
    private val frameRate: Int = 30,
    private val iFrameIntervalSec: Int = 1,
    private val videoCodec: VideoCodec = VideoCodec.H264,
    /** true=TS 里加 AAC 音轨（声画同投）；音帧由 [writeAudioFrame] 喂入。 */
    private val withAudio: Boolean = false,
    /** 发一个 TS 包到底层扇出；返回 false=发生了丢弃（拥塞信号）。 */
    private val emit: (ByteArray) -> Boolean,
    private val onLog: (String) -> Unit = { Log.i("ScreenMirror", it) },
    /**
     * HLS 模式：每个关键帧写出前回调（参数=归零 pts 微秒），供切片器在此关闭/新开分片。
     * 非 null 时每个关键帧前强制重发 PAT/SDT/PMT（HLS 每个分片必须以节目表起手）。
     */
    private val onSegmentBoundary: ((Long) -> Unit)? = null,
    /**
     * >0 时按该周期（毫秒）强制编码器产关键帧（HLS 降延迟用：分片边界只在关键帧上，
     * 分片时长=关键帧间隔；0.5s 分片比 1s 分片让 AVPlayer 的直播缓冲量减半）。
     * 0=不强制，用编码器自带 [iFrameIntervalSec] GOP（DLNA 裸流路径）。
     */
    private val syncFrameIntervalMs: Long = 0,
    /**
     * fMP4 旁路（仅 HLS FMP4 模式接线，默认 null 零开销零行为变化）：
     * 编码器原始帧回调——SPS/PPS（裸 NAL）、Annex-B 视频帧（不含 AUD，AUD 是 TsMuxer 内部加的）、
     * AAC 帧（带 ADTS 头，pts 已按 HLS 惯例 +1s）。数据与 TS 路径同源同 pts 基准。
     */
    private val onCodecConfig: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null,
    private val onRawVideoFrame: ((data: ByteArray, ptsUs: Long, keyframe: Boolean) -> Unit)? = null,
    private val onRawAudioFrame: ((adtsFrame: ByteArray, ptsUs: Long) -> Unit)? = null,
) {
    @Volatile private var codec: MediaCodec? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var inputSurface: android.view.Surface? = null
    private var frameRepeater: com.local.huaweicast.FrameRepeater? = null
    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {}
    // ---- 丢包自愈（drop-until-IDR）----
    /** 拥塞恢复中：丢弃非关键帧的 TS 包，直到下个 IDR 干净续流。 */
    @Volatile private var gating = false
    /** 首帧视频 pts（归零基准）；音轨 pts 从 0 起，与此同基。 */
    @Volatile private var ptsBase = -1L
    @Volatile private var inKeyframe = false
    @Volatile private var droppedInFrame = false
    private val packetSink: (ByteArray) -> Unit = { pkt ->
        if (gating && !inKeyframe) {
            // 恢复中：非关键帧的包直接丢弃（喂损坏 P 帧只会让播放器冻屏）
        } else if (!emit(pkt)) {
            droppedInFrame = true; gating = true; requestSyncFrame()
        }
    }
    private val muxer = TsMuxer(audioPid = if (withAudio) 0x102 else null, videoCodec = videoCodec, onPacket = packetSink)
    private val parameterSets = VideoParameterSets(videoCodec)
    @Volatile private var running = false
    private var drainThread: Thread? = null
    /** 编码器是否已产出所选格式的完整参数集。 */
    @Volatile var ready = false; private set
    /** start 失败原因（vivo 等 ROM 屏蔽 logcat，诊断须透传到 UI 状态行）。 */
    @Volatile var lastError: String? = null; private set

    /** 开始采集编码。[projection] 须已授权且前台 Service 已起。返回 false=编码器初始化失败（原因见 [lastError]）。 */
    fun start(projection: MediaProjection): Boolean {
        this.projection = projection
        // Android 14+ 强制：createVirtualDisplay 前必须 registerCallback，否则 SecurityException
        runCatching { projection.registerCallback(projectionCallback, null) }
        val encoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { type -> type.equals(videoCodec.mime(), ignoreCase = true) }
        }
        if (encoderInfo == null) {
            lastError = "此手机没有可用的 ${videoCodec.label()} 硬件编码器，请选择其他编码格式"
            return false
        }
        val fmt = MediaFormat.createVideoFormat(videoCodec.mime(), width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // AVC Baseline / HEVC Main with B frames disabled keeps PTS equal to DTS.
            runCatching {
                setInteger(MediaFormat.KEY_PROFILE, if (videoCodec == VideoCodec.H265) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            // CBR 恒定码率：压掉码率突发，避免高动态画面瞬间打爆下行队列（冻屏根因之一）
            runCatching {
                val capabilities = encoderInfo.getCapabilitiesForType(videoCodec.mime()).encoderCapabilities
                setInteger(MediaFormat.KEY_BITRATE_MODE, if (capabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }
        var candidate: MediaCodec? = null
        val c = try {
            MediaCodec.createByCodecName(encoderInfo.name).also { candidate = it }.apply {
                try {
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (t: Throwable) {
                    // 兜底：个别 ROM 编码器拒绝显式 profile，去掉 profile 重配一次
                    onLog("${videoCodec.label()} profile 配置被拒(${t.message})，去 profile 重试")
                    reset()
                    fmt.removeKey(MediaFormat.KEY_PROFILE)
                    configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }
        } catch (t: Throwable) {
            runCatching { candidate?.release() }
            lastError = "${videoCodec.label()} 编码器初始化: ${t.javaClass.simpleName} ${t.message}"
            onLog("编码器初始化失败: ${t.message}")
            return false
        }
        val surface = try {
            val s = c.createInputSurface()
            c.start()
            s
        } catch (t: Throwable) {
            lastError = "编码器start: ${t.javaClass.simpleName} ${t.message}"
            onLog("编码器 start 失败: ${t.message}")
            runCatching { c.release() }
            return false
        }
        codec = c
        inputSurface = surface
        try {
            val repeater = com.local.huaweicast.FrameRepeater(surface, width, height, frameRate)
            frameRepeater = repeater
            display = projection.createVirtualDisplay(
                "airsonic-mirror", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, repeater.surface, null, null
            )
        } catch (t: Throwable) {
            lastError = "虚拟屏: ${t.javaClass.simpleName} ${t.message}"
            onLog("createVirtualDisplay 失败: ${t.message}")
            frameRepeater?.release(); frameRepeater = null
            runCatching { c.stop() }; runCatching { c.release() }; codec = null
            surface.release(); inputSurface = null
            return false
        }
        running = true
        drainThread = thread(isDaemon = true, name = "airsonic-screen-drain") { drainLoop(c) }
        if (syncFrameIntervalMs > 0) {
            // HLS 降延迟：周期强制关键帧 → 分片边界密度 = 该周期（独立于拥塞恢复的节流通道）
            thread(isDaemon = true, name = "airsonic-sync-tick") {
                while (running) {
                    try { Thread.sleep(syncFrameIntervalMs) } catch (_: InterruptedException) { break }
                    if (!running) break
                    runCatching {
                        codec?.setParameters(android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    }
                }
            }
        }
        onLog("录屏编码已启动 ${videoCodec.label()} ${encoderInfo.name} ${width}x${height}@${frameRate} bitrate=$bitRate")
        return true
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (t: Throwable) {
                if (running) onLog("dequeue 异常: ${t.message}")
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = c.outputFormat
                    onLog("编码器格式: $output")
                    for (key in arrayOf("csd-0", "csd-1", "csd-2")) {
                        output.getByteBuffer(key)?.duplicate()?.let { buffer ->
                            val data = ByteArray(buffer.remaining()); buffer.get(data); acceptParameterSets(data)
                        }
                    }
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.get(data)
                        // handleFrame 内含 muxer/SPS 解析等可能抛异常的链路（尤其畸形输入），
                        // 绝不能让它杀死 drain 线程——编码器无人消费 = 镜像无声冻屏
                        try { handleFrame(data, info) } catch (t: Throwable) {
                            onLog("handleFrame 异常已吞: ${t.javaClass.simpleName} ${t.message}")
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    @Synchronized
    private fun handleFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val configNals = if (config) VideoParameterSets.splitAnnexB(data) else emptyList()
        if (config || !ready) acceptParameterSets(data)
        if (config && configNals.none { videoCodec.isVideoNal(it) }) return
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || configNals.any { videoCodec.isKeyNal(it) }
        if (keyframe) { inKeyframe = true; droppedInFrame = false }
        // pts 归零对齐：视频编码器给的是 nanoTime 系大数，音轨 pts 从 0 起——同基才能声画同步。
        // HLS 模式整体 +1s：对齐 ffmpeg 首 PCR≈0.7s 的惯例（0 起播在 CoreMedia 下有拒产样本风险）。
        if (ptsBase < 0) ptsBase = info.presentationTimeUs - hlsPtsOffsetUs
        val relPts = info.presentationTimeUs - ptsBase
        if (keyframe && onSegmentBoundary != null) {
            muxer.forcePatPmt()              // HLS：每片起手 PAT/SDT/PMT → SPS/PPS → IDR
            onSegmentBoundary.invoke(relPts)
        }
        muxer.writeVideoFrame(data, relPts, keyframe)
        onRawVideoFrame?.invoke(data, relPts, keyframe)   // fMP4 旁路：原始帧（无 AUD）
        if (keyframe) {
            inKeyframe = false
            if (!droppedInFrame) gating = false   // 本关键帧完整发出 → 拥塞恢复完成
        }
    }

    /** HLS 模式（有切片回调）pts 起点偏移；DLNA 裸流保持 0 起不动既有行为。 */
    private val hlsPtsOffsetUs = if (onSegmentBoundary != null) 1_000_000L else 0L

    /** 喂一帧 AAC（带 ADTS 头）；ptsUs 与视频同基（0 起，HLS 模式同样 +1s）。TS 音轨在无音轨模式丢弃；fMP4 旁路回调不受 withAudio 影响（由接收方自行丢弃）。 */
    fun writeAudioFrame(adtsFrame: ByteArray, ptsUs: Long) {
        if (withAudio) muxer.writeAudioFrame(adtsFrame, ptsUs + hlsPtsOffsetUs)
        onRawAudioFrame?.invoke(adtsFrame, ptsUs + hlsPtsOffsetUs)
    }

    /** Shares video CLOCK_MONOTONIC origin; serializes complete PES frames and HLS boundaries. */
    @Synchronized
    fun writeCapturedAudio(adtsFrame: ByteArray, monotonicPtsUs: Long) {
        if (!running || ptsBase < 0 || gating) return
        val relative = monotonicPtsUs - ptsBase
        if (relative < hlsPtsOffsetUs) return
        if (withAudio) muxer.writeAudioFrame(adtsFrame, relative)
    }

    /** 让编码器立刻产一个关键帧（新观众接入/传输丢包时用，把花屏窗口压到最短）。 */
    @Volatile private var lastSyncRequestAt = 0L
    fun requestSyncFrame() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncRequestAt < 500) return   // 节流：拥塞持续时最多 2 次/秒
        lastSyncRequestAt = now
        runCatching {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    /**
     * 新观众接入：丢包直到下个 IDR + 该 IDR 前强制重发 PAT/SDT/PMT。
     * 严格播放器（AVPlayer）流起点必须是「PAT/SDT/PMT → SPS/PPS → IDR」，否则探流即判 unsupported。
     * 代价：老观众闪过一个 GOP（≤1s），可接受。
     */
    fun prepareCleanJoin() {
        gating = true
        muxer.forcePatPmt()
        requestSyncFrame()
    }

    fun stop() {
        runCatching { display?.release() }; display = null
        frameRepeater?.release(); frameRepeater = null
        running = false
        drainThread?.join(1500)
        runCatching { display?.release() }; display = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }; codec = null
        runCatching { inputSurface?.release() }; inputSurface = null
        projection?.let { runCatching { it.unregisterCallback(projectionCallback) } }
        projection = null
        onLog("录屏编码已停止")
    }

    private fun acceptParameterSets(data: ByteArray) {
        parameterSets.accept(data)
        if (!parameterSets.complete()) return
        val sps = parameterSets.sps(); val pps = parameterSets.pps()
        if (videoCodec == VideoCodec.H265) muxer.setVpsSpsPps(parameterSets.vps(), sps, pps)
        else {
            muxer.setSpsPps(sps, pps)
            onCodecConfig?.invoke(sps.copyOfRange(4, sps.size), pps.copyOfRange(4, pps.size))
        }
        if (!ready) onLog("${videoCodec.label()} 参数集已就绪")
        ready = true
    }
}
