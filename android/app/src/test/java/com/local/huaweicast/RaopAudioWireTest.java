package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class RaopAudioWireTest {
    @Test public void audioUsesBigEndianRtpAndRawAccessUnitWithoutAacHbrHeaders() {
        byte[] raw = {(byte) 0x8c, 0x12, 0x34};
        byte[] packet = RaopAudioWire.audioPacket(raw, 0xabcd, 0x12345678L, 0x9abcdef0L, true);
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xe0, (byte) 0xab, (byte) 0xcd,
                0x12, 0x34, 0x56, 0x78, (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
                (byte) 0x8c, 0x12, 0x34}, packet);
        raw[0] = 0;
        assertEquals(0x8c, packet[12] & 0xff);
        assertEquals(0x60, RaopAudioWire.audioPacket(raw, 0, 0, 0, false)[1] & 0xff);
    }

    @Test public void rejectsInvalidAudioLengthsAndUnsignedFields() {
        for (byte[] raw : new byte[][]{null, new byte[0], new byte[RaopAudioWire.MAX_ACCESS_UNIT_SIZE + 1]}) {
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.audioPacket(raw, 0, 0, 0, false));
        }
        for (int sequence : new int[]{-1, 65536}) {
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.audioPacket(new byte[]{1}, sequence, 0, 0, false));
        }
        for (long value : new long[]{-1, 0x1_00000000L}) {
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.audioPacket(new byte[]{1}, 0, value, 0, false));
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.audioPacket(new byte[]{1}, 0, 0, value, false));
        }
        assertEquals(12 + RaopAudioWire.MAX_ACCESS_UNIT_SIZE,
                RaopAudioWire.audioPacket(new byte[RaopAudioWire.MAX_ACCESS_UNIT_SIZE], 65535, 0xffffffffL, 0xffffffffL, false).length);
    }

    @Test public void sequenceAndSampleTimestampsWrapWithoutAssumingCodecFrameLength() {
        assertEquals(0, RaopAudioWire.nextSequence(65535));
        assertEquals(0xffff, RaopAudioWire.nextSequence(65534));
        assertEquals(224, RaopAudioWire.timestampAfter(0xffffff00L, 480));
        assertEquals(256, RaopAudioWire.timestampAfter(0xffffff00L, 512));
        assertEquals(0xffffffffL, RaopAudioWire.timestampAfter(0, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.nextSequence(-1));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.timestampAfter(0, -1));
    }

    @Test public void sampleClockSupportsArbitraryRatesAndLongElapsedTimesWithoutOverflow() {
        assertEquals(44100, RaopAudioWire.timestampAt(0, 1_000_000, 44100));
        assertEquals(24000, RaopAudioWire.timestampAt(0, 500_000, 48000));
        assertEquals(0, RaopAudioWire.timestampAt(0xffffffffL, 1_000_000, 1));
        long elapsed = Long.MAX_VALUE;
        int rate = Integer.MAX_VALUE;
        long expected = BigInteger.valueOf(elapsed).multiply(BigInteger.valueOf(rate))
                .divide(BigInteger.valueOf(1_000_000)).and(BigInteger.valueOf(0xffffffffL)).longValue();
        assertEquals(expected, RaopAudioWire.timestampAt(0, elapsed, rate));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.timestampAt(0, -1, 44100));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.timestampAt(0, 0, 0));
    }

    @Test public void syncPacketCarriesExplicitRtpAnchorNtpAndNextPacketTimestamp() {
        byte[] packet = RaopAudioWire.syncPacket(7, 0xffffeeefL, 0x0123456789abcdefL, 1, true);
        assertArrayEquals(new byte[]{(byte) 0x90, (byte) 0xd4, 0, 7, (byte) 0xff, (byte) 0xff,
                (byte) 0xee, (byte) 0xef, 1, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab,
                (byte) 0xcd, (byte) 0xef, 0, 0, 0, 1}, packet);
        assertEquals(0x80, RaopAudioWire.syncPacket(4, 1, 2, 3, false)[0] & 0xff);
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.syncPacket(7, -1, 0, 0, false));
    }

    @Test public void timingReplyEchoesTransmitClockAsOriginAndKeepsSequence() {
        long origin = 0x83c117ccafba9b32L;
        long received = 0x83c117ccb012ceb6L;
        long transmitted = 0x83c117ccb0141047L;
        byte[] request = RaopAudioWire.timingRequest(7, origin);
        assertEquals(32, request.length);
        assertEquals(0xd2, request[1] & 0xff);
        assertEquals(new RaopAudioWire.TimingRequest(7, origin), RaopAudioWire.parseTimingRequest(request));
        byte[] reply = RaopAudioWire.timingReply(request, received, transmitted);
        assertEquals(32, reply.length);
        assertEquals(0xd3, reply[1] & 0xff);
        assertEquals(new RaopAudioWire.TimingReply(7, origin, received, transmitted), RaopAudioWire.parseTimingReply(reply));
        assertEquals(0, ByteBuffer.wrap(reply).getInt(4));
    }

    @Test public void timingParsersRejectWrongTypesFlagsAndPacketLengths() {
        byte[] valid = RaopAudioWire.timingRequest(65535, 1);
        for (int length : new int[]{0, 8, 31, 33, 48}) {
            byte[] malformed = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseTimingRequest(malformed));
        }
        for (int flag : new int[]{0, 0x81, 0x90, 0xa0, 0xc0}) {
            byte[] malformed = valid.clone(); malformed[0] = (byte) flag;
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseTimingRequest(malformed));
        }
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseTimingReply(valid));
        byte[] reply = RaopAudioWire.timingReply(valid, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseTimingRequest(reply));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.timingReply(null, 2, 3));
    }

    @Test public void resendRequestIsEightBytesAndSequenceRangeCanWrap() {
        byte[] request = {(byte) 0x80, (byte) 0xd5, 0x12, 0x34, (byte) 0xff, (byte) 0xfe, 0, 4};
        var parsed = RaopAudioWire.parseResendRequest(request, 32);
        assertEquals(0x1234, parsed.sequence()); assertEquals(65534, parsed.firstSequence()); assertEquals(4, parsed.count());
        assertEquals(65534, parsed.sequenceAt(0)); assertEquals(65535, parsed.sequenceAt(1));
        assertEquals(0, parsed.sequenceAt(2)); assertEquals(1, parsed.sequenceAt(3));
        assertThrows(IllegalArgumentException.class, () -> parsed.sequenceAt(-1));
        assertThrows(IllegalArgumentException.class, () -> parsed.sequenceAt(4));
    }

    @Test public void resendRequestsAreStrictAndBoundedRatherThanSilentlyClamped() {
        byte[] valid = {(byte) 0x80, (byte) 0xd5, 0, 1, 0, 2, 0, 8};
        for (int length : new int[]{0, 7, 9, 12}) {
            byte[] malformed = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(malformed, 32));
        }
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(valid, 7));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(valid, 0));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(valid, 257));
        for (int count : new int[]{0, 257, 65535}) {
            byte[] malformed = valid.clone(); ByteBuffer.wrap(malformed).putShort(6, (short) count);
            assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(malformed, 256));
        }
        byte[] wrongType = valid.clone(); wrongType[1] = (byte) 0xd6;
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(wrongType, 32));
        byte[] extraSource = valid.clone(); extraSource[0] = (byte) 0x81;
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.parseResendRequest(extraSource, 32));
    }

    @Test public void resendReplyPreservesOriginalRtpPacketAndDoesNotMutateCache() {
        byte[] original = RaopAudioWire.audioPacket(new byte[]{(byte) 0x8c, 2, 3}, 65535, 0xfffffffeL, 123, true);
        byte[] reply = RaopAudioWire.resendReply(1, original);
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xd6, 0, 1}, Arrays.copyOf(reply, 4));
        assertArrayEquals(original, Arrays.copyOfRange(reply, 4, reply.length));
        reply[4] = 0;
        assertEquals(0x80, original[0] & 0xff);
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.resendReply(1, null));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.resendReply(1, new byte[12]));
        byte[] extension = original.clone(); extension[0] = (byte) 0x90;
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.resendReply(1, extension));
    }

    @Test public void sharedClockNtpConversionDoesNotAddAnEpoch() {
        assertEquals(0, RaopAudioWire.ntpTimestampUs(0));
        assertEquals(0x0000000180000000L, RaopAudioWire.ntpTimestampUs(1_500_000));
        assertEquals(0xffffffff80000000L, RaopAudioWire.ntpTimestampUs(0xffffffffL * 1_000_000 + 500_000));
        assertEquals(LegacyMirrorWire.ntpTimestamp(123_456_789), RaopAudioWire.ntpTimestampUs(123_456_789));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.ntpTimestampUs(-1));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.ntpTimestampUs(0x1_00000000L * 1_000_000));
    }

    @Test public void unixNtpConversionIsExplicitAndSupportsEraRollover() {
        assertEquals(0x83aa7e8000000000L, RaopAudioWire.ntpFromUnixMicros(0));
        assertEquals(0x83aa7e7f80000000L, RaopAudioWire.ntpFromUnixMicros(-500_000));
        assertEquals(0, RaopAudioWire.ntpFromUnixMicros(-2208988800L * 1_000_000));
        assertEquals(0, RaopAudioWire.ntpFromUnixMicros((0x1_00000000L - 2208988800L) * 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> RaopAudioWire.ntpFromUnixMicros(-2208988800L * 1_000_000 - 1));
    }
}
