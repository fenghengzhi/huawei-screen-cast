package com.local.huaweicast

import com.airsonic.sender.streaming.TsMuxer
import org.junit.Assert.*
import org.junit.Test

class AudioMuxerTest {
    @Test fun `ADTS declares AAC LC 48k stereo and exact frame length`() {
        val payload = ByteArray(512) { it.toByte() }
        val frame = AacAdts.wrap(payload)
        assertEquals(0xff, frame[0].toInt() and 255)
        assertEquals(0xf1, frame[1].toInt() and 255)
        assertEquals(1, (frame[2].toInt() ushr 6) and 3)
        assertEquals(3, (frame[2].toInt() ushr 2) and 15)
        assertEquals(2, ((frame[2].toInt() and 1) shl 2) or ((frame[3].toInt() ushr 6) and 3))
        assertEquals(frame.size, ((frame[3].toInt() and 3) shl 11) or ((frame[4].toInt() and 255) shl 3) or ((frame[5].toInt() ushr 5) and 7))
        assertArrayEquals(payload, frame.copyOfRange(7, frame.size))
    }
    @Test(expected = IllegalArgumentException::class) fun `oversized AAC cannot corrupt ADTS length`() {
        AacAdts.wrap(ByteArray(8185))
    }
    @Test fun `both video codecs declare AAC and preserve audio PES and timestamp`() {
        for (codec in VideoCodec.values()) {
            val packets = ArrayList<ByteArray>()
            val muxer = TsMuxer(audioPid = 0x102, videoCodec = codec, onPacket = { packets.add(it) })
            muxer.writeVideoFrame(byteArrayOf(0,0,0,1,0x65), 1000000, true)
            val frame = AacAdts.wrap(byteArrayOf(1,2,3,4))
            muxer.writeAudioFrame(frame, 1020000)
            val pmt = packets.first { pid(it) == 0x1000 }
            val section = offset(pmt) + 1
            assertEquals(codec.streamType(), pmt[section + 12].toInt() and 255)
            assertEquals(0x0f, pmt[section + 17].toInt() and 255)
            val length = ((pmt[section+1].toInt() and 15) shl 8) or (pmt[section+2].toInt() and 255)
            assertEquals(0L, TsMuxer.crc32Mpeg(pmt, section, length+3))
            val audio = packets.single { pid(it) == 0x102 }
            val pes = offset(audio)
            assertEquals(0xc0, audio[pes+3].toInt() and 255)
            val p = audio.copyOfRange(pes+9, pes+14).map { it.toLong() and 255 }
            val pts = ((p[0] and 14) shl 29) or (p[1] shl 22) or ((p[2] and 254) shl 14) or (p[3] shl 7) or (p[4] shr 1)
            assertEquals(91800L, pts)
            assertArrayEquals(frame, audio.copyOfRange(pes+14, 188))
        }
    }
    private fun pid(p: ByteArray) = ((p[1].toInt() and 31) shl 8) or (p[2].toInt() and 255)
    private fun offset(p: ByteArray) = if (p[3].toInt() and 32 != 0) 5 + (p[4].toInt() and 255) else 4
}
