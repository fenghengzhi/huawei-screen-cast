package com.local.huaweicast

import android.media.MediaCodecInfo
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackAudioCaptureConfigTest {
    @Test fun preservesLegacyDefaultsAndAllowsRawEld() {
        PlaybackAudioCapture({ _, _ -> }, {}).stop()
        for (rate in listOf(44100, 48000)) {
            PlaybackAudioCapture({ _, _ -> }, {}, sampleRate = rate,
                aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectELD, rawOutput = true).stop()
        }
    }

    @Test fun rejectsIncorrectAdtsOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackAudioCapture({ _, _ -> }, {}, aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackAudioCapture({ _, _ -> }, {}, sampleRate = 44100)
        }
    }

    @Test fun stopBeforeStartIsIdempotent() {
        val capture = PlaybackAudioCapture({ _, _ -> }, {})
        capture.stop()
        capture.stop()
    }

    @Test fun nativeEncoderRequiresRawEldMode() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackAudioCapture({ _, _ -> }, {}, useNativeEld = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackAudioCapture({ _, _ -> }, {}, rawOutput = true, useNativeEld = true)
        }
        PlaybackAudioCapture({ _, _ -> }, {}, aacProfile = MediaCodecInfo.CodecProfileLevel.AACObjectELD,
            rawOutput = true, useNativeEld = true).stop()
    }
}
