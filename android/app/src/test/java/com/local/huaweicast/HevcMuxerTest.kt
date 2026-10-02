package com.local.huaweicast

import com.airsonic.sender.streaming.TsMuxer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class HevcMuxerTest {
    @Test fun `HEVC stream type CRC and keyframe parameter order are correct`() {
        val packets = ArrayList<ByteArray>()
        val muxer = TsMuxer(videoCodec = VideoCodec.H265, onPacket = { packets.add(it) })
        val vps = byteArrayOf(0,0,0,1,0x40,1,10)
        val sps = byteArrayOf(0,0,0,1,0x42,1,11)
        val pps = byteArrayOf(0,0,0,1,0x44,1,12)
        val frame = byteArrayOf(0,0,0,1,0x26,1,13)
        muxer.setVpsSpsPps(vps,sps,pps)
        muxer.writeVideoFrame(frame,1_000_000,true)
        val pmt = packets.first { pid(it) == 0x1000 }
        val section = payloadOffset(pmt) + 1
        assertEquals(0x24, pmt[section+12].toInt() and 255)
        val length = ((pmt[section+1].toInt() and 15) shl 8) or (pmt[section+2].toInt() and 255)
        assertEquals(0L, TsMuxer.crc32Mpeg(pmt,section,length+3))
        val aud = byteArrayOf(0,0,0,1,0x46,1,0x50)
        assertArrayEquals(aud + vps + sps + pps + frame, elementary(packets))
        packets.clear()
        muxer.writeVideoFrame(byteArrayOf(0,0,0,1,2,1,42),1_050_000,false)
        assertArrayEquals(aud + byteArrayOf(0,0,0,1,2,1,42), elementary(packets))
    }
    @Test fun `AVC remains stream type 1b with AVC delimiter`() {
        val packets = ArrayList<ByteArray>()
        val muxer = TsMuxer { packets.add(it) }
        muxer.setSpsPps(byteArrayOf(0,0,0,1,0x67,10),byteArrayOf(0,0,0,1,0x68,11))
        muxer.writeVideoFrame(byteArrayOf(0,0,0,1,0x65,12),0,true)
        val pmt = packets.first { pid(it) == 0x1000 }
        assertEquals(0x1b, pmt[payloadOffset(pmt)+13].toInt() and 255)
        assertArrayEquals(byteArrayOf(0,0,0,1,9,0xf0.toByte()),elementary(packets).copyOfRange(0,6))
    }
    private fun pid(packet: ByteArray) = ((packet[1].toInt() and 31) shl 8) or (packet[2].toInt() and 255)
    private fun payloadOffset(packet: ByteArray) = if (packet[3].toInt() and 0x20 != 0) 5 + (packet[4].toInt() and 255) else 4
    private fun elementary(packets: List<ByteArray>): ByteArray {
        val result = ByteArrayOutputStream()
        for (packet in packets.filter { pid(it) == 0x101 }) {
            var offset = payloadOffset(packet)
            if (packet[1].toInt() and 0x40 != 0) offset += 9 + (packet[offset+8].toInt() and 255)
            result.write(packet,offset,188-offset)
        }
        return result.toByteArray()
    }
}
