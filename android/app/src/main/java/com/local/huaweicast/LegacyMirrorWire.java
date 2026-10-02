package com.local.huaweicast;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.BinaryPropertyListWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/** Independent encoding of the publicly documented legacy AirPlay mirror wire format. */
public final class LegacyMirrorWire {
    public static final int HEADER_SIZE = 128;
    private static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    private LegacyMirrorWire() {}

    public static byte[] streamInfo(long deviceId, long sessionId, int latencyMs) throws IOException {
        if (deviceId < 0 || deviceId > 0xffffffffffffL || latencyMs < 0 || latencyMs > 5000) {
            throw new IllegalArgumentException("Invalid mirror stream parameters");
        }
        NSDictionary info = new NSDictionary();
        info.put("deviceID", deviceId);
        info.put("sessionID", sessionId);
        info.put("version", "130.16");
        info.put("latencyMs", latencyMs);
        info.put("fpsInfo", new NSArray(0));
        info.put("timestampInfo", new NSArray(0));
        return BinaryPropertyListWriter.writeToArray(info);
    }

    public static byte[] codecPacket(byte[] sps, byte[] pps, int width, int height, long timestampUs) {
        return packet(1, avcConfiguration(sps, pps), width, height, timestampUs);
    }

    public static byte[] videoPacket(byte[] annexB, int width, int height, long timestampUs) {
        return packet(0, annexBToAvcc(annexB), width, height, timestampUs);
    }

    public static byte[] heartbeatPacket() {
        byte[] result = new byte[HEADER_SIZE];
        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(4, (short) 2);
        header.putShort(6, (short) 0x1e);
        return result;
    }

    public static byte[] avcConfiguration(byte[] sps, byte[] pps) {
        byte[] sequence = singleNal(sps, 7);
        byte[] picture = singleNal(pps, 8);
        if (sequence.length < 4 || picture.length < 2
                || sequence.length > 65535 || picture.length > 65535) {
            throw new IllegalArgumentException("Invalid H.264 parameter set length");
        }
        ByteBuffer result = ByteBuffer.allocate(11 + sequence.length + picture.length);
        result.put((byte) 1);
        result.put(sequence[1]).put(sequence[2]).put(sequence[3]);
        result.put((byte) 0xff).put((byte) 0xe1);
        result.putShort((short) sequence.length).put(sequence);
        result.put((byte) 1).putShort((short) picture.length).put(picture);
        return result.array();
    }

    public static byte[] annexBToAvcc(byte[] annexB) {
        if (annexB == null || annexB.length == 0 || annexB.length > MAX_PAYLOAD_SIZE
                || !hasStartCode(annexB)) {
            throw new IllegalArgumentException("Expected an Annex-B H.264 access unit");
        }
        List<byte[]> nals = strictAnnexB(annexB);
        if (nals.isEmpty()) throw new IllegalArgumentException("Empty H.264 access unit");
        long length = 0;
        for (byte[] nal : nals) {
            validateNal(nal);
            length += 4L + nal.length;
        }
        if (length > MAX_PAYLOAD_SIZE) throw new IllegalArgumentException("H.264 access unit too large");
        ByteBuffer result = ByteBuffer.allocate((int) length);
        for (byte[] nal : nals) result.putInt(nal.length).put(nal);
        return result.array();
    }

    public static long ntpTimestamp(long timestampUs) {
        if (timestampUs < 0 || timestampUs / 1_000_000L > 0xffffffffL) {
            throw new IllegalArgumentException("Timestamp out of NTP range");
        }
        long seconds = timestampUs / 1_000_000L;
        long fraction = ((timestampUs % 1_000_000L) << 32) / 1_000_000L;
        return (seconds << 32) | fraction;
    }

    public static byte[] ntpReply(byte[] request, long receiveUs, long sendUs) {
        if (request == null || request.length != 48 || (request[0] & 7) != 3
                || ((request[0] >>> 3) & 7) != 4 || sendUs < receiveUs) {
            throw new IllegalArgumentException("Invalid mirror NTP request");
        }
        byte[] response = new byte[48];
        response[0] = 0x24;
        response[1] = 1;
        response[2] = 2;
        response[3] = (byte) 0xe8;
        response[12] = 'A'; response[13] = 'I'; response[14] = 'R'; response[15] = 'P';
        System.arraycopy(request, 40, response, 24, 8);
        ByteBuffer output = ByteBuffer.wrap(response);
        output.putLong(32, ntpTimestamp(receiveUs));
        output.putLong(40, ntpTimestamp(sendUs));
        return response;
    }

    private static byte[] packet(int type, byte[] payload, int width, int height, long timestampUs) {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                || payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Invalid mirror packet dimensions or length");
        }
        byte[] result = new byte[HEADER_SIZE + payload.length];
        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, payload.length);
        header.putShort(4, (short) type);
        header.putShort(6, (short) 6);
        header.putLong(8, ntpTimestamp(timestampUs));
        // The legacy codec header repeats the encoded display dimensions.
        if (type == 1) {
            header.putFloat(16, width);
            header.putFloat(20, height);
        }
        header.putFloat(40, width);
        header.putFloat(44, height);
        // Keep unnegotiated extension fields zero: G2 drops packets with floats at 56/60.
        System.arraycopy(payload, 0, result, HEADER_SIZE, payload.length);
        return result;
    }

    private static byte[] singleNal(byte[] data, int expectedType) {
        if (data == null || data.length == 0 || data.length > 65539) {
            throw new IllegalArgumentException("Missing H.264 parameter set");
        }
        byte[] nal;
        if (hasStartCode(data)) {
            List<byte[]> nals = strictAnnexB(data);
            if (nals.size() != 1) throw new IllegalArgumentException("Expected one H.264 parameter set");
            nal = nals.get(0);
        } else {
            nal = Arrays.copyOf(data, data.length);
        }
        validateNal(nal);
        if ((nal[0] & 31) != expectedType) throw new IllegalArgumentException("Unexpected H.264 NAL type");
        return nal;
    }

    private static boolean hasStartCode(byte[] data) {
        return data.length >= 3 && data[0] == 0 && data[1] == 0
                && (data[2] == 1 || (data.length >= 4 && data[2] == 0 && data[3] == 1));
    }

    private static List<byte[]> strictAnnexB(byte[] data) {
        List<byte[]> nals = VideoParameterSets.splitAnnexB(data);
        int prefixes = 0;
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            if (data[i + 2] == 1) {
                prefixes++;
                i += 2;
            } else if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                prefixes++;
                i += 3;
            }
        }
        if (prefixes != nals.size()) throw new IllegalArgumentException("Empty Annex-B NAL unit");
        return nals;
    }

    private static void validateNal(byte[] nal) {
        if (nal.length == 0 || (nal[0] & 0x80) != 0 || (nal[0] & 31) == 0 || (nal[0] & 31) > 23) {
            throw new IllegalArgumentException("Invalid H.264 NAL unit");
        }
    }
}
