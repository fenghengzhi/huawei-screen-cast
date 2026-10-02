package com.local.huaweicast

import android.media.projection.MediaProjection
import com.airsonic.sender.screen.ScreenMirrorCaster

class MirrorEngine {
    private var caster: ScreenMirrorCaster? = null
    val server = MirrorHttpServer()
    fun start(projection: MediaProjection, dpi: Int, quality: CastQuality): Boolean {
        val encoder = ScreenMirrorCaster(
            width = quality.width(), height = quality.height(),
            dpi = dpi, bitRate = quality.bitRate(),
            frameRate = quality.fps(), iFrameIntervalSec = 1,
            videoCodec = quality.codec(),
            emit = { server.emit(it) },
            onSegmentBoundary = { server.boundary(it) },
            syncFrameIntervalMs = 1000
        )
        caster = encoder
        server.onJoin = Runnable { encoder.prepareCleanJoin() }
        server.start(5000, true)
        return encoder.start(projection)
    }
    fun error(): String = caster?.lastError ?: "录屏编码未就绪"
    fun ready(): Boolean = caster?.ready == true && server.segmentCount() >= 2
    fun stop() { caster?.stop(); caster = null; server.stop() }
}
