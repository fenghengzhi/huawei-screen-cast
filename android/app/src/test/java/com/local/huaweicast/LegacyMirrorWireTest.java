package com.local.huaweicast;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.PropertyListParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyMirrorWireTest {
    private static final byte[] SPS = {0x67, 0x42, 0, 0x1f, 12, 34};
    private static final byte[] PPS = {0x68, 56, 78};
    private static final byte[] HEVC_VPS = {0x40, 1, 12, 1};
    private static final byte[] HEVC_SPS = {0x42, 1, 1, 96};
    private static final byte[] HEVC_PPS = {0x44, 1, (byte) 0xc0};

    @Test public void streamInfoIsBinaryPlistWithoutCredentialsOrEncryptionKeys() throws Exception {
        byte[] bytes = LegacyMirrorWire.streamInfo(0x123456789abcL, -1234567, 90);
        assertEquals("bplist00", new String(bytes, 0, 8, StandardCharsets.US_ASCII));
        NSDictionary info = (NSDictionary) PropertyListParser.parse(bytes);
        assertEquals(6, info.count());
        assertEquals(0x123456789abcL, ((NSNumber) info.objectForKey("deviceID")).longValue());
        assertEquals(-1234567, ((NSNumber) info.objectForKey("sessionID")).longValue());
        assertEquals(90, ((NSNumber) info.objectForKey("latencyMs")).intValue());
        assertEquals("130.16", info.objectForKey("version").toString());
        assertEquals(0, ((NSArray) info.objectForKey("fpsInfo")).count());
        assertEquals(0, ((NSArray) info.objectForKey("timestampInfo")).count());
        assertNull(info.objectForKey("param1"));
        assertNull(info.objectForKey("param2"));
    }

    @Test public void codecRecordUsesBigEndianLengthsAndAcceptsBothAnnexBPrefixes() {
        byte[] expected = {1, 0x42, 0, 0x1f, (byte) 0xff, (byte) 0xe1, 0, 6,
                0x67, 0x42, 0, 0x1f, 12, 34, 1, 0, 3, 0x68, 56, 78};
        assertArrayEquals(expected, LegacyMirrorWire.avcConfiguration(SPS, PPS));
        assertArrayEquals(expected, LegacyMirrorWire.avcConfiguration(
                new byte[]{0, 0, 0, 1, 0x67, 0x42, 0, 0x1f, 12, 34},
                new byte[]{0, 0, 1, 0x68, 56, 78}));
    }

    @Test public void codecPacketUsesLittleEndianHeaderAndFixedDimensions() {
        byte[] data = LegacyMirrorWire.codecPacket(SPS, PPS, 960, 540, 1_500_000);
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(data.length - 128, header.getInt(0));
        assertEquals(1, header.getShort(4));
        assertEquals(6, header.getShort(6));
        assertEquals(0x0000000180000000L, header.getLong(8));
        assertEquals(960, header.getFloat(16), 0);
        assertEquals(540, header.getFloat(20), 0);
        assertEquals(960, header.getFloat(40), 0);
        assertEquals(540, header.getFloat(44), 0);
        assertEquals(0, header.getFloat(48), 0);
        assertEquals(0, header.getFloat(52), 0);
        assertEquals(0, header.getFloat(56), 0);
        assertEquals(0, header.getFloat(60), 0);
        assertArrayEquals(LegacyMirrorWire.avcConfiguration(SPS, PPS), Arrays.copyOfRange(data, 128, data.length));
    }

    @Test public void framesConvertAllNalLengthsToBigEndianWithoutStartCodes() {
        byte[] annexB = {0, 0, 0, 1, 0x65, 11, 12, 0, 0, 1, 6, 13};
        byte[] expected = {0, 0, 0, 3, 0x65, 11, 12, 0, 0, 0, 2, 6, 13};
        assertArrayEquals(expected, LegacyMirrorWire.annexBToAvcc(annexB));
        byte[] packet = LegacyMirrorWire.videoPacket(annexB, 960, 540, 2_000_000);
        ByteBuffer header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(expected.length, header.getInt(0));
        assertEquals(0, header.getShort(4));
        assertEquals(6, header.getShort(6));
        assertEquals(0x0000000200000000L, header.getLong(8));
        assertEquals(960, header.getFloat(40), 0);
        assertEquals(540, header.getFloat(44), 0);
        assertEquals(0, header.getFloat(48), 0);
        assertEquals(0, header.getFloat(52), 0);
        assertEquals(0, header.getFloat(56), 0);
        assertEquals(0, header.getFloat(60), 0);
        assertArrayEquals(expected, Arrays.copyOfRange(packet, 128, packet.length));
    }

    @Test public void hevcParameterPacketUsesTypeOneAndOrderedReceiverNalArrays() {
        byte[] data = LegacyMirrorWire.hevcCodecPacket(HEVC_VPS, HEVC_SPS, HEVC_PPS, 1920, 1080, 2_250_000);
        byte[] expected = {1, 0, 0, 0, (byte) 0xff, (byte) 0xe2,
                0, 4, 0x40, 1, 12, 1, 0, 4, 0x42, 1, 1, 96,
                1, 0, 3, 0x44, 1, (byte) 0xc0};
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(128 + expected.length, data.length);
        assertEquals(expected.length, header.getInt(0));
        assertEquals(1, header.getShort(4));
        assertEquals(6, header.getShort(6));
        assertEquals(0x0000000240000000L, header.getLong(8));
        assertEquals(1920, header.getFloat(40), 0);
        assertEquals(1080, header.getFloat(44), 0);
        assertEquals(1920, header.getFloat(16), 0);
        assertEquals(1080, header.getFloat(20), 0);
        for (int offset : new int[]{48, 52, 56, 60}) assertEquals(0, header.getInt(offset));
        assertArrayEquals(expected, Arrays.copyOfRange(data, 128, data.length));
        assertArrayEquals(expected, LegacyMirrorWire.hevcConfiguration(HEVC_VPS, HEVC_SPS, HEVC_PPS));
    }

    @Test public void hevcParameterSetsAcceptRawAndBothAnnexBPrefixLengths() {
        byte[] expected = LegacyMirrorWire.hevcCodecPacket(HEVC_VPS, HEVC_SPS, HEVC_PPS, 960, 540, 0);
        assertArrayEquals(expected, LegacyMirrorWire.hevcCodecPacket(
                new byte[]{0, 0, 0, 1, 0x40, 1, 12, 1},
                new byte[]{0, 0, 1, 0x42, 1, 1, 96},
                new byte[]{0, 0, 0, 1, 0x44, 1, (byte) 0xc0}, 960, 540, 0));
    }

    @Test public void hevcAudProtectsIdrAndTsaFromOneLegacySeiRemoval() {
        byte[] annexB = {0, 0, 0, 1, 0x26, 1, 9, 10, 0, 0, 1, 0x06, 1, 11};
        byte[] expected = {0, 0, 0, 3, 0x46, 1, 0x50, 0, 0, 0, 4, 0x26, 1, 9, 10,
                0, 0, 0, 3, 0x06, 1, 11};
        byte[] packet = LegacyMirrorWire.hevcVideoPacket(annexB, 960, 540, 1_500_000);
        ByteBuffer header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(expected.length, header.getInt(0));
        assertEquals(0, header.getShort(4)); assertEquals(6, header.getShort(6));
        assertEquals(0x0000000180000000L, header.getLong(8));
        assertEquals(960, header.getFloat(40), 0); assertEquals(540, header.getFloat(44), 0);
        assertEquals(0, header.getInt(56)); assertEquals(0, header.getInt(60));
        byte[] payload = Arrays.copyOfRange(packet, 128, packet.length);
        assertArrayEquals(expected, payload);
        assertEquals(6, payload[4] & 31);
        int afterRemovedAud = 4 + ByteBuffer.wrap(payload).getInt();
        assertEquals(4, ByteBuffer.wrap(payload).getInt(afterRemovedAud));
        assertEquals(19, (payload[afterRemovedAud + 4] & 0x7e) >>> 1);
        assertEquals(3, (payload[afterRemovedAud + 12] & 0x7e) >>> 1);
    }

    @Test public void hevcExistingLeadingAudIsNotDuplicated() {
        byte[] annexB = {0, 0, 1, 0x46, 1, 0x50, 0, 0, 0, 1, 0x26, 1, 9};
        byte[] expected = {0, 0, 0, 3, 0x46, 1, 0x50, 0, 0, 0, 3, 0x26, 1, 9};
        byte[] packet = LegacyMirrorWire.hevcVideoPacket(annexB, 960, 540, 0);
        assertEquals(128 + expected.length, packet.length);
        assertArrayEquals(expected, Arrays.copyOfRange(packet, 128, packet.length));
    }

    @Test public void invalidHevcNalHeadersAndFramingAreRejected() {
        for (byte[] data : new byte[][]{null, new byte[0], new byte[]{0x26, 1},
                new byte[]{0, 0, 1}, new byte[]{0, 0, 1, 0x26},
                new byte[]{0, 0, 1, (byte) 0xa6, 1}, new byte[]{0, 0, 1, 0x26, 8},
                new byte[]{0, 0, 1, 0, 0, 1, 0x26, 1}, new byte[]{0, 0, 1, 0x26, 1, 0, 0, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcVideoPacket(data, 960, 540, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(HEVC_SPS, HEVC_VPS, HEVC_PPS, 960, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(new byte[]{0x40}, HEVC_SPS, HEVC_PPS, 960, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(HEVC_VPS, new byte[]{0x42, 0}, HEVC_PPS, 960, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(HEVC_VPS, HEVC_SPS, new byte[]{(byte) 0xc4, 1}, 960, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(
                new byte[]{0, 0, 1, 0x40, 1, 0, 0, 1, 0x40, 1}, HEVC_SPS, HEVC_PPS, 960, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(HEVC_VPS, HEVC_SPS, HEVC_PPS, 0, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcVideoPacket(new byte[]{0, 0, 1, 0x26, 1}, 960, 540, -1));
    }

    @Test public void hevcSizeLimitsAccountForAddedLengthsAndAud() {
        byte[] huge = new byte[16 * 1024 * 1024];
        huge[3] = 1; huge[4] = 0x26; huge[5] = 1;
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcVideoPacket(huge, 960, 540, 0));
        byte[] hugeParameter = new byte[65536]; hugeParameter[0] = 0x40; hugeParameter[1] = 1;
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(hugeParameter, HEVC_SPS, HEVC_PPS, 960, 540, 0));
    }

    @Test public void hevcParameterSetsNeverExceedTheReceiverExpandedBuffer() {
        byte[] vps = new byte[40]; vps[0] = 0x40; vps[1] = 1;
        byte[] sps = new byte[60]; sps[0] = 0x42; sps[1] = 1;
        byte[] pps = new byte[16]; pps[0] = 0x44; pps[1] = 1;
        assertEquals(128, vps.length + sps.length + pps.length + 12);
        // The wire envelope is one byte longer than the receiver's expanded Annex-B data.
        assertEquals(129, LegacyMirrorWire.hevcConfiguration(vps, sps, pps).length);
        byte[] oversized = Arrays.copyOf(pps, 17);
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcConfiguration(vps, sps, oversized));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.hevcCodecPacket(vps, sps, oversized, 960, 540, 0));
    }

    @Test public void h264VideoRemainsUnchangedWithoutHevcAud() {
        byte[] packet = LegacyMirrorWire.videoPacket(new byte[]{0, 0, 1, 0x65, 1, 2}, 960, 540, 0);
        assertArrayEquals(new byte[]{0, 0, 0, 3, 0x65, 1, 2}, Arrays.copyOfRange(packet, 128, packet.length));
        assertEquals(1, ByteBuffer.wrap(LegacyMirrorWire.codecPacket(SPS, PPS, 960, 540, 0))
                .order(ByteOrder.LITTLE_ENDIAN).getShort(4));
    }

    @Test public void heartbeatHasNoPayloadAndSeparateFlags() {
        byte[] data = LegacyMirrorWire.heartbeatPacket();
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(128, data.length);
        assertEquals(0, header.getInt(0));
        assertEquals(2, header.getShort(4));
        assertEquals(0x1e, header.getShort(6));
        assertEquals(0, header.getLong(8));
    }

    @Test public void ntpReplyEchoesOriginAndUsesNetworkByteOrder() {
        byte[] request = new byte[48];
        request[0] = 0x23;
        ByteBuffer.wrap(request).putLong(40, 0x0123456789abcdefL);
        byte[] response = LegacyMirrorWire.ntpReply(request, 1_250_000, 1_500_000);
        ByteBuffer fields = ByteBuffer.wrap(response);
        assertEquals(0x24, response[0]);
        assertEquals(1, response[1]);
        assertEquals("AIRP", new String(response, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(0x0123456789abcdefL, fields.getLong(24));
        assertEquals(0x0000000140000000L, fields.getLong(32));
        assertEquals(0x0000000180000000L, fields.getLong(40));
    }

    @Test public void invalidInputIsRejectedBeforeEmission() {
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.avcConfiguration(PPS, SPS));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.avcConfiguration(new byte[]{0x67}, PPS));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.annexBToAvcc(new byte[]{0x65, 1}));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.annexBToAvcc(new byte[]{0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.annexBToAvcc(new byte[]{0, 0, 1, 0, 0, 1, 0x65, 1}));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.annexBToAvcc(new byte[]{0, 0, 1, 0x65, 1, 0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.annexBToAvcc(new byte[]{0, 0, 1, (byte) 0xe5}));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.codecPacket(SPS, PPS, 0, 540, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.ntpTimestamp(-1));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.ntpReply(new byte[48], 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LegacyMirrorWire.streamInfo(-1, 0, 90));
    }
}
