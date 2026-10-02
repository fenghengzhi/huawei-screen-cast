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
