// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.sender.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TsMuxer 结构级单测：包长/sync/continuity counter/PAT-PMT CRC/PES-PTS/PCR/stuffing。 */
class TsMuxerTest {

    // 真实 Baseline SPS/PPS（320x240），让样本流对 ffprobe 可见
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e, 0xda.toByte(), 0x02, 0x80.toByte(), 0x2d, 0xc8.toByte())
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x06, 0xe2.toByte())

    private fun mux(frames: Int = 90, payloadSize: Int = 2000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val m = TsMuxer { pkt -> out.write(pkt) }
        m.setSpsPps(sps, pps)
        for (i in 0 until frames) {
            val idr = i % 30 == 0
            val nal = ByteArray(payloadSize) { (it + i).toByte() }
            nal[0] = 0; nal[1] = 0; nal[2] = 0; nal[3] = 1
            nal[4] = if (idr) 0x65 else 0x41           // IDR / non-IDR slice
            m.writeVideoFrame(nal, ptsUs = i * 33_333L, keyframe = idr)
        }
        return out.toByteArray()
    }

    @Test
    fun `所有包 188B 且 sync 0x47`() {
        val ts = mux()
        assertEquals(0, ts.size % 188)
        for (i in ts.indices step 188) assertEquals(0x47, ts[i].toInt() and 0xFF)
    }

    @Test
    fun `同一 PID 的 continuity counter 连续`() {
        val ts = mux()
        val lastCc = HashMap<Int, Int>()
        for (off in ts.indices step 188) {
            val pid = ((ts[off + 1].toInt() and 0x1F) shl 8) or (ts[off + 2].toInt() and 0xFF)
            val cc = ts[off + 3].toInt() and 0x0F
            val prev = lastCc[pid]
            if (prev != null) assertEquals("pid=$pid 在包$off cc 跳变", (prev + 1) and 0xF, cc)
            lastCc[pid] = cc
        }
        assertTrue("应包含视频 PID 0x101", lastCc.containsKey(0x101))
        assertTrue("应包含 PAT PID 0", lastCc.containsKey(0))
        assertTrue("应包含 PMT PID 0x1000", lastCc.containsKey(0x1000))
    }

    @Test
    fun `PAT 与 PMT section CRC32 正确且周期性重发`() {
        val ts = mux(frames = 90, payloadSize = 200)
        var pat = 0; var pmt = 0
        for (off in ts.indices step 188) {
            val pusi = ts[off + 1].toInt() and 0x40 != 0
            val pid = ((ts[off + 1].toInt() and 0x1F) shl 8) or (ts[off + 2].toInt() and 0xFF)
            if (!pusi || (pid != 0 && pid != 0x1000)) continue
            // 跳过 adaptation（如有），payload[0] = pointer_field
            val afc = (ts[off + 3].toInt() ushr 4) and 3
            var p = off + 4
            if (afc == 3 || afc == 2) p += 1 + (ts[off + 4].toInt() and 0xFF)
            val payloadOff = p + 1                    // pointer_field
            val sectionLength = ((ts[payloadOff + 1].toInt() and 0x0F) shl 8) or (ts[payloadOff + 2].toInt() and 0xFF)
            val crc = TsMuxer.crc32Mpeg(ts, payloadOff, 3 + sectionLength - 4)
            val stored = read32(ts, payloadOff + 3 + sectionLength - 4)
            assertEquals("pid=$pid CRC", stored, crc)
            if (pid == 0) pat++ else pmt++
        }
        assertTrue("PAT 应重发多次（$pat）", pat >= 2)
        assertTrue("PMT 应重发多次（$pmt）", pmt >= 2)
    }

    @Test
    fun `PES 首包带 PCR 且等于 PTS(90kHz)`() {
        val out = ArrayList<ByteArray>()
        val m = TsMuxer { pkt -> out.add(pkt) }
        m.setSpsPps(sps, pps)
        m.writeVideoFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), ptsUs = 1_000_000L, keyframe = true)
        // 找到视频 PID 的首个 PUSI 包
        val first = out.first { pkt ->
            val pid = ((pkt[1].toInt() and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)
            pid == 0x101 && pkt[1].toInt() and 0x40 != 0
        }
        val afc = (first[3].toInt() ushr 4) and 3
        assertEquals(3, afc)                          // adaptation + payload
        val afl = first[4].toInt() and 0xFF
        assertTrue(afl >= 7)
        assertEquals(0x10, first[5].toInt() and 0x10) // PCR flag
        val pcr = readPcr33(first, 6)
        assertEquals(90_000L, pcr)                    // 1s → 90000
        // PES 头紧随 adaptation
        val pesOff = 5 + afl
        assertEquals(0, first[pesOff].toInt()); assertEquals(0, first[pesOff + 1].toInt())
        assertEquals(1, first[pesOff + 2].toInt()); assertEquals(0xE0, first[pesOff + 3].toInt() and 0xFF)
        val pts = readPts33(first, pesOff + 9)
        assertEquals(90_000L, pts)
        // PTS 首字节必须是 '0010'+高 3 位+marker=1（0x21）；Kotlin shl/or 同优先级左结合曾算出 0x41，AVPlayer 拒播
        assertEquals(0x21, first[pesOff + 9].toInt() and 0xFF)
    }

    @Test
    fun `关键帧前置 SPS 与 PPS`() {
        val out = ArrayList<ByteArray>()
        val m = TsMuxer { pkt -> out.add(pkt) }
        m.setSpsPps(sps, pps)
        m.writeVideoFrame(byteArrayOf(0, 0, 0, 1, 0x65, 9), ptsUs = 0, keyframe = true)
        // 拼出视频 PES payload，应依次含 SPS、PPS、IDR
        val pesPayload = java.io.ByteArrayOutputStream()
        for (pkt in out) {
            val pid = ((pkt[1].toInt() and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)
            if (pid != 0x101) continue
            val afc = (pkt[3].toInt() ushr 4) and 3
            var p = 4
            if (afc == 3 || afc == 2) p += 1 + (pkt[4].toInt() and 0xFF)
            val pusi = pkt[1].toInt() and 0x40 != 0
            if (pusi) p += 14                          // 跳过 PES 头
            pesPayload.write(pkt, p, 188 - p)
        }
        val pl = pesPayload.toByteArray()
        // payload 布局：AUD(6B) → SPS → PPS → IDR（AUD 是 CoreMedia HLS 子流的样本边界锚点）
        assertTrue(pl.size >= 6 + sps.size + pps.size + 6)
        assertTrue("AUD 在最前", pl.copyOfRange(0, 6).contentEquals(byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())))
        assertTrue("SPS 紧随 AUD", pl.copyOfRange(6, 6 + sps.size).contentEquals(sps))
        assertTrue("PPS 紧随其后", pl.copyOfRange(6 + sps.size, 6 + sps.size + pps.size).contentEquals(pps))
    }

    @Test
    fun `AAC 音轨进 PMT 且音频 PES 走独立 PID`() {
        val out = ArrayList<ByteArray>()
        val m = TsMuxer(audioPid = 0x102) { pkt -> out.add(pkt) }
        m.setSpsPps(sps, pps)
        m.writeVideoFrame(byteArrayOf(0, 0, 0, 1, 0x65, 1), 0, keyframe = true)
        m.writeAudioFrame(byteArrayOf(1, 2, 3, 4), ptsUs = 5000)
        val pmt = out.first { pkt ->
            val pid = ((pkt[1].toInt() and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)
            pid == 0x1000
        }
        val afc = (pmt[3].toInt() ushr 4) and 3
        var p = 4
        if (afc == 3 || afc == 2) p += 1 + (pmt[4].toInt() and 0xFF)
        val sec = pmt.copyOfRange(p + 1, 188)          // pointer 后到包尾（含 stuffing，查找即可）
        assertTrue("PMT 应含 0x0F(AAC)", sec.toList().windowed(5).any {
            it[0] == 0x0F.toByte() && (it[1].toInt() and 0xE0) == 0xE0 &&
                (((it[1].toInt() and 0x1F) shl 8) or (it[2].toInt() and 0xFF)) == 0x102
        })
        assertTrue("应有音频 PID 0x102 的包", out.any {
            (((it[1].toInt() and 0x1F) shl 8) or (it[2].toInt() and 0xFF)) == 0x102
        })
    }

    /** 输出一段样本 TS 到 build/ 供 ffprobe 人工/脚本验证。 */
    @Test
    fun `写出样本 TS 文件`() {
        val ts = mux(frames = 120, payloadSize = 1500)
        val dir = java.io.File("build/tsmuxer"); dir.mkdirs()
        java.io.File(dir, "sample.ts").writeBytes(ts)
        assertTrue(ts.size > 120 * 188)

        // 声画样本：audioPid 开 AAC 音轨，ADTS 假帧验证结构（ffprobe 应列出 video+audio 两条流）
        val out = java.io.ByteArrayOutputStream()
        val m2 = TsMuxer(audioPid = 0x102) { pkt -> out.write(pkt) }
        m2.setSpsPps(sps, pps)
        for (i in 0 until 120) {
            val idr = i % 30 == 0
            val nal = ByteArray(1500) { (it + i).toByte() }
            nal[0] = 0; nal[1] = 0; nal[2] = 0; nal[3] = 1
            nal[4] = if (idr) 0x65 else 0x41
            m2.writeVideoFrame(nal, ptsUs = i * 33_333L, keyframe = idr)
            // ADTS 头(7B) + 假负载；pts 按 AAC 帧率 44100/1024 ≈ 23.2ms/帧
            val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x40, 0x02, 0xDF.toByte(), 0xFC.toByte()) + ByteArray(10)
            m2.writeAudioFrame(adts, ptsUs = i * 23_219L)
        }
        java.io.File(dir, "sample_av.ts").writeBytes(out.toByteArray())
    }

    private fun read32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readPcr33(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 25) or ((b[off + 1].toLong() and 0xFF) shl 17) or
            ((b[off + 2].toLong() and 0xFF) shl 9) or ((b[off + 3].toLong() and 0xFF) shl 1) or
            ((b[off + 4].toLong() ushr 7) and 1)

    private fun readPts33(b: ByteArray, off: Int): Long =
        (((b[off].toLong() ushr 1) and 7) shl 30) or ((b[off + 1].toLong() and 0xFF) shl 22) or
            (((b[off + 2].toLong() ushr 1) and 0x7F) shl 15) or ((b[off + 3].toLong() and 0xFF) shl 7) or
            ((b[off + 4].toLong() ushr 1) and 0x7F)
}
