package com.local.huaweicast;

import java.nio.ByteBuffer;

/** Pure packet encoding; the caller owns negotiation, authorization, pacing and peer validation. */
public final class RaopAudioWire {
    public static final int AUDIO_HEADER_SIZE = 12;
    public static final int MAX_ACCESS_UNIT_SIZE = 8192;
    public static final int MAX_RESEND_PACKETS = 256;
    private static final long UINT32_MASK = 0xffffffffL;
    private static final long NTP_UNIX_OFFSET_SECONDS = 2208988800L;

    private RaopAudioWire() {}

    public record TimingRequest(int sequence, long transmitNtp) {
        public TimingRequest { requireUnsigned16(sequence); }
    }
    public record TimingReply(int sequence, long originNtp, long receiveNtp, long transmitNtp) {
        public TimingReply { requireUnsigned16(sequence); }
    }
    public record ResendRequest(int sequence, int firstSequence, int count) {
        public ResendRequest {
            requireUnsigned16(sequence); requireUnsigned16(firstSequence);
            if (count < 1 || count > MAX_RESEND_PACKETS) throw new IllegalArgumentException("Invalid resend count");
        }
        public int sequenceAt(int index) {
            if (index < 0 || index >= count) throw new IllegalArgumentException("Invalid resend index");
            return (firstSequence + index) & 0xffff;
        }
    }

    /**
     * RAOP mode=AAC-eld carries a raw access unit after the fixed RTP header, without ADTS
     * or AAC-hbr's AU header section. A different payload format requires separate negotiation.
     * Sources: https://github.com/FDH2/UxPlay/blob/master/lib/raop_buffer.c
     * and https://github.com/FDH2/UxPlay/blob/master/lib/raop_rtp.c .
     */
    public static byte[] audioPacket(byte[] rawAccessUnit, int sequence, long rtpTimestamp,
                                     long ssrc, boolean first) {
        requireUnsigned16(sequence); requireUnsigned32(rtpTimestamp); requireUnsigned32(ssrc);
        if (rawAccessUnit == null || rawAccessUnit.length == 0 || rawAccessUnit.length > MAX_ACCESS_UNIT_SIZE) {
            throw new IllegalArgumentException("Invalid audio access unit length");
        }
        return ByteBuffer.allocate(AUDIO_HEADER_SIZE + rawAccessUnit.length)
                .put((byte) 0x80).put((byte) (first ? 0xe0 : 0x60)).putShort((short) sequence)
                .putInt((int) rtpTimestamp).putInt((int) ssrc).put(rawAccessUnit).array();
    }

    /**
     * 20-byte RAOP sync: RTP time minus negotiated latency, NTP anchor, next RTP time.
     * Caller supplies both RTP values so no codec frame length or playback delay is assumed.
     * https://github.com/philippe44/libraop/blob/master/src/raop_client.c (_raopcl_send_sync)
     */
    public static byte[] syncPacket(int sequence, long timestampMinusLatency, long ntp32_32,
                                    long nextTimestamp, boolean first) {
        requireUnsigned16(sequence); requireUnsigned32(timestampMinusLatency); requireUnsigned32(nextTimestamp);
        return ByteBuffer.allocate(20).put((byte) (first ? 0x90 : 0x80)).put((byte) 0xd4)
                .putShort((short) sequence).putInt((int) timestampMinusLatency)
                .putLong(ntp32_32).putInt((int) nextTimestamp).array();
    }

    /** RAOP timing uses an 8-byte control header plus three NTP 32.32 timestamps. */
    public static byte[] timingRequest(int sequence, long transmitNtp) {
        requireUnsigned16(sequence);
        return ByteBuffer.allocate(32).put((byte) 0x80).put((byte) 0xd2).putShort((short) sequence)
                .putInt(0).putLong(0).putLong(0).putLong(transmitNtp).array();
    }

    public static TimingRequest parseTimingRequest(byte[] packet) {
        requireControlPacket(packet, 32, 0xd2);
        ByteBuffer data = ByteBuffer.wrap(packet);
        return new TimingRequest(unsigned16(data.getShort(2)), data.getLong(24));
    }

    public static byte[] timingReply(byte[] request, long receiveNtp, long transmitNtp) {
        TimingRequest parsed = parseTimingRequest(request);
        return ByteBuffer.allocate(32).put((byte) 0x80).put((byte) 0xd3).putShort((short) parsed.sequence())
                .putInt(0).putLong(parsed.transmitNtp()).putLong(receiveNtp).putLong(transmitNtp).array();
    }

    public static TimingReply parseTimingReply(byte[] packet) {
        requireControlPacket(packet, 32, 0xd3);
        ByteBuffer data = ByteBuffer.wrap(packet);
        return new TimingReply(unsigned16(data.getShort(2)), data.getLong(8), data.getLong(16), data.getLong(24));
    }

    /**
     * Actual resend requests are exactly 8 bytes, including the 4-byte prefix.
     * https://github.com/FDH2/UxPlay/blob/master/lib/raop_rtp.c (raop_rtp_resend_callback)
     */
    public static ResendRequest parseResendRequest(byte[] packet, int maxPackets) {
        if (maxPackets < 1 || maxPackets > MAX_RESEND_PACKETS) throw new IllegalArgumentException("Invalid resend limit");
        requireControlPacket(packet, 8, 0xd5);
        ByteBuffer data = ByteBuffer.wrap(packet);
        int count = unsigned16(data.getShort(6));
        if (count < 1 || count > maxPackets) throw new IllegalArgumentException("Resend request exceeds limit");
        return new ResendRequest(unsigned16(data.getShort(2)), unsigned16(data.getShort(4)), count);
    }

    /** Wraps the original cached packet unchanged; this does not re-encode or advance its timestamp. */
    public static byte[] resendReply(int sequence, byte[] fullAudioPacket) {
        requireUnsigned16(sequence);
        if (fullAudioPacket == null || fullAudioPacket.length <= AUDIO_HEADER_SIZE
                || fullAudioPacket.length > AUDIO_HEADER_SIZE + MAX_ACCESS_UNIT_SIZE
                || (fullAudioPacket[0] & 0xff) != 0x80 || (fullAudioPacket[1] & 0x7f) != 96) {
            throw new IllegalArgumentException("Expected a fixed-header audio RTP packet");
        }
        return ByteBuffer.allocate(4 + fullAudioPacket.length).put((byte) 0x80).put((byte) 0xd6)
                .putShort((short) sequence).put(fullAudioPacket).array();
    }

    /** Encodes a nonnegative shared clock in NTP 32.32 form without choosing or adding an epoch. */
    public static long ntpTimestampUs(long clockMicros) {
        if (clockMicros < 0 || clockMicros / 1_000_000L > UINT32_MASK) {
            throw new IllegalArgumentException("Clock is outside one NTP era");
        }
        return (clockMicros / 1_000_000L << 32) | fraction(clockMicros % 1_000_000L);
    }

    /** Explicit wall-clock conversion, including NTP's 2036 era rollover. Do not mix with a session clock. */
    public static long ntpFromUnixMicros(long unixMicros) {
        long ntpSeconds = Math.floorDiv(unixMicros, 1_000_000L) + NTP_UNIX_OFFSET_SECONDS;
        if (ntpSeconds < 0) throw new IllegalArgumentException("Wall clock predates NTP epoch");
        return ((ntpSeconds & UINT32_MASK) << 32) | fraction(Math.floorMod(unixMicros, 1_000_000L));
    }

    public static int nextSequence(int sequence) {
        requireUnsigned16(sequence);
        return (sequence + 1) & 0xffff;
    }

    /** samples counts PCM frames per channel, not the interleaved stereo sample total. */
    public static long timestampAfter(long timestamp, long samples) {
        requireUnsigned32(timestamp);
        if (samples < 0) throw new IllegalArgumentException("Negative sample count");
        return (timestamp + (samples & UINT32_MASK)) & UINT32_MASK;
    }

    /** Derives a timestamp at an elapsed time without cumulative rounding or long multiplication overflow. */
    public static long timestampAt(long initialTimestamp, long elapsedMicros, int sampleRate) {
        requireUnsigned32(initialTimestamp);
        if (elapsedMicros < 0 || sampleRate <= 0) throw new IllegalArgumentException("Invalid sample clock");
        long wholeSamples = ((elapsedMicros / 1_000_000L) & UINT32_MASK) * sampleRate;
        long partialSamples = (elapsedMicros % 1_000_000L) * sampleRate / 1_000_000L;
        return (initialTimestamp + (wholeSamples & UINT32_MASK) + partialSamples) & UINT32_MASK;
    }

    private static long fraction(long micros) { return (micros << 32) / 1_000_000L; }
    private static int unsigned16(short value) { return value & 0xffff; }
    private static void requireControlPacket(byte[] packet, int length, int type) {
        if (packet == null || packet.length != length || (packet[0] & 0xff) != 0x80
                || (packet[1] & 0xff) != type) throw new IllegalArgumentException("Invalid RAOP control packet");
    }
    private static void requireUnsigned16(int value) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException("Value outside unsigned 16-bit range");
    }
    private static void requireUnsigned32(long value) {
        if (value < 0 || value > UINT32_MASK) throw new IllegalArgumentException("Value outside unsigned 32-bit range");
    }
}
