package com.local.huaweicast

import android.media.projection.MediaProjection
import com.airsonic.sender.screen.ScreenMirrorCaster

class MirrorEngine(private val onError: (String) -> Unit) {
    private var caster: ScreenMirrorCaster? = null
    private var audio: PlaybackAudioCapture? = null
    val server = MirrorHttpServer()
    fun start(projection: MediaProjection, dpi: Int, quality: CastQuality): Boolean {
        val encoder = ScreenMirrorCaster(
            width = quality.width(), height = quality.height(),
            dpi = dpi, bitRate = quality.bitRate(),
            frameRate = quality.fps(), iFrameIntervalSec = 1,
            videoCodec = quality.codec(),
            withAudio = true,
            emit = { server.emit(it) },
            onSegmentBoundary = { server.boundary(it) },
            syncFrameIntervalMs = 1000,
            onError = onError
        )
        caster = encoder
        server.onJoin = Runnable { encoder.prepareCleanJoin() }
        server.start(5000, true)
        if (!encoder.start(projection)) return false
        audio = PlaybackAudioCapture(encoder::writeCapturedAudio, onError).also { it.start(projection) }
        return true
    }
    fun error(): String = caster?.lastError ?: "录屏编码未就绪"
    fun ready(): Boolean = caster?.ready == true && server.segmentCount() >= 2
    fun stop() { audio?.stop(); audio = null; caster?.stop(); caster = null; server.stop() }
}
