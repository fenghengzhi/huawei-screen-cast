package com.local.huaweicast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Independent framing for authenticated native Lelink media, not the legacy PC route. */
public final class LelinkMediaWire {
    public static final int HEADER_SIZE = 128;
    public static final int MAX_PAYLOAD_SIZE = 3 * 1024 * 1024;

    private LelinkMediaWire() {}

    public static byte[] codecPacket(VideoCodec codec, byte[] vps, byte[] sps, byte[] pps,
                                     int width, int height, long timestampUs) {
        if (codec == null) throw invalid("Missing video codec");
        byte[] config = codec == VideoCodec.H265
                ? hevcConfiguration(vps, sps, pps, width, height) : avcConfiguration(sps, pps);
        return packet(1, config, width, height, timestampUs);
    }

    public static byte[] avcConfiguration(byte[] sps, byte[] pps) {
        byte[] sequence = parameterSet(sps, VideoCodec.H264, 7);
        byte[] picture = parameterSet(pps, VideoCodec.H264, 8);
        if (sequence.length < 4 || picture.length < 2) throw invalid("Truncated AVC parameter set");
        validateParameterSize(sequence, picture);
        int profile = sequence[1] & 255;
        boolean extended = profile == 100 || profile == 110 || profile == 122 || profile == 144;
        int chroma = 1, lumaDepth = 0, chromaDepth = 0;
        if (extended) {
            Bits bits = new Bits(rbsp(sequence, 4));
            bits.ue();
            chroma = bits.ue();
            if (chroma > 3) throw invalid("Unsupported AVC chroma format");
            if (chroma == 3) bits.read(1);
            lumaDepth = bits.ue();
            chromaDepth = bits.ue();
            if (lumaDepth > 7 || chromaDepth > 7) throw invalid("Unsupported AVC bit depth");
        }
        ByteBuffer output = ByteBuffer.allocate(11 + sequence.length + picture.length + (extended ? 4 : 0));
        output.put((byte) 1).put(sequence[1]).put(sequence[2]).put(sequence[3]);
        output.put((byte) 0xff).put((byte) 0xe1).putShort((short) sequence.length).put(sequence);
        output.put((byte) 1).putShort((short) picture.length).put(picture);
        if (extended) output.put((byte) (0xfc | chroma)).put((byte) (0xf8 | lumaDepth))
                .put((byte) (0xf8 | chromaDepth)).put((byte) 0);
        return output.array();
    }

    public static byte[] hevcConfiguration(byte[] vps, byte[] sps, byte[] pps, int width, int height) {
        validateDimensions(width, height);
        byte[] video = parameterSet(vps, VideoCodec.H265, 32);
        byte[] sequence = parameterSet(sps, VideoCodec.H265, 33);
        byte[] picture = parameterSet(pps, VideoCodec.H265, 34);
        validateParameterSize(video, sequence, picture);
        HevcProfile profile = hevcProfile(video, sequence);
        int arraysSize = 15 + video.length + sequence.length + picture.length;
        int hvccSize = 8 + 23 + arraysSize;
        ByteBuffer output = ByteBuffer.allocate(86 + hvccSize);
        output.putInt(output.capacity()).put("hvc1".getBytes(StandardCharsets.US_ASCII));
        output.put(new byte[6]).putShort((short) 1);
        output.put(new byte[16]);
        output.putShort((short) width).putShort((short) height);
        output.putInt(0x00480000).putInt(0x00480000).putInt(0).putShort((short) 1);
        output.put(new byte[32]).putShort((short) 0x18).putShort((short) 0xffff);
        output.putInt(hvccSize).put("hvcC".getBytes(StandardCharsets.US_ASCII));
        output.put((byte) 1).put(profile.tierLevel);
        output.putShort((short) 0xf000).put((byte) 0xfc);
        output.put((byte) (0xfc | profile.chroma)).put((byte) (0xf8 | profile.lumaDepth));
        output.put((byte) (0xf8 | profile.chromaDepth)).putShort((short) 0);
        output.put((byte) ((profile.layers << 3) | (profile.nested << 2) | 3)).put((byte) 3);
        putHevcArray(output, 32, video);
        putHevcArray(output, 33, sequence);
        putHevcArray(output, 34, picture);
        return output.array();
    }

    public static final class VideoEncryptor implements AutoCloseable {
        private final VideoCodec codec;
        private final byte[] key;
        private byte[] iv;
        private boolean closed;

        public VideoEncryptor(byte[] mediaSeed, VideoCodec codec) {
            if (codec == null) throw invalid("Missing video codec");
            this.codec = codec;
            key = derive(mediaSeed, "LELINK-MIRROR-VIDEO-KEY");
            iv = derive(mediaSeed, "LELINK-MIRROR-VIDEO-IV");
        }

        public synchronized byte[] videoPacket(byte[] annexB, boolean keyframe,
                                                int width, int height, long timestampUs) {
            if (closed) throw new IllegalStateException("Video encryptor is closed");
            validateDimensions(width, height);
            ntpTimestamp(timestampUs);
            byte[] payload = videoPayload(annexB, codec, keyframe);
            int firstType = codec == VideoCodec.H265 ? (payload[4] & 0x7e) >>> 1 : payload[4] & 31;
            boolean idr = codec == VideoCodec.H265 ? firstType == 19 || firstType == 20 : firstType == 5;
            // Native v2 encrypts only this portion of an IDR and chains its IV across IDRs.
            int encryptedLength = idr ? ((payload.length - 5) >>> 1) & ~15 : 0;
            if (encryptedLength > 0) {
                byte[] encrypted = encrypt(key, iv, payload, 5, encryptedLength);
                System.arraycopy(encrypted, 0, payload, 5, encrypted.length);
                iv = Arrays.copyOfRange(encrypted, encrypted.length - 16, encrypted.length);
            }
            return packet(0, payload, width, height, timestampUs);
        }

        @Override public synchronized void close() {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            closed = true;
        }
    }

    public static final class AudioEncryptor implements AutoCloseable {
        private final byte[] key;
        private final byte[] iv;
        private boolean closed;

        public AudioEncryptor(byte[] mediaSeed) {
            key = derive(mediaSeed, "LELINK-MIRROR-AUDIO-KEY");
            iv = derive(mediaSeed, "LELINK-MIRROR-AUDIO-IV");
        }

        public synchronized byte[] encryptPayload(byte[] rawAac) {
            if (closed) throw new IllegalStateException("Audio encryptor is closed");
            if (rawAac == null || rawAac.length == 0 || rawAac.length > 65507 - 12) {
                throw invalid("Invalid AAC packet length");
            }
            byte[] payload = rawAac.clone();
            int length = payload.length & ~15;
            if (length > 0) {
                byte[] encrypted = encrypt(key, iv, payload, 0, length);
                System.arraycopy(encrypted, 0, payload, 0, length);
            }
            return payload;
        }

        @Override public synchronized void close() {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            closed = true;
        }
    }

    private static byte[] videoPayload(byte[] annexB, VideoCodec codec, boolean keyframe) {
        if (annexB == null || annexB.length == 0 || annexB.length > MAX_PAYLOAD_SIZE) {
            throw invalid("Invalid video access unit length");
        }
        List<byte[]> source = annexB(annexB);
        List<byte[]> filtered = new ArrayList<>();
        long length = 0;
        boolean seenVcl = false;
        for (byte[] nal : source) {
            validateNal(nal, codec);
            int type = codec.nalType(nal);
            if (codec == VideoCodec.H265 && type >= 16 && type <= 23 && type != 19 && type != 20) {
                throw invalid("Native Lelink requires an HEVC IDR, not CRA/BLA");
            }
            boolean parameterOrAud = codec == VideoCodec.H265 ? type >= 32 && type <= 35
                    : type == 7 || type == 8 || type == 9;
            boolean prefixSei = !seenVcl && type == (codec == VideoCodec.H265 ? 39 : 6);
            if (parameterOrAud || prefixSei) continue;
            if (!seenVcl && !codec.isVideoNal(nal)) throw invalid("Unexpected leading video NAL");
            seenVcl |= codec.isVideoNal(nal);
            filtered.add(nal);
            length += 4L + nal.length;
        }
        if (!seenVcl || length > MAX_PAYLOAD_SIZE) throw invalid("Missing or oversized video frame");
        int firstType = codec.nalType(filtered.get(0));
        boolean idr = codec == VideoCodec.H265 ? firstType == 19 || firstType == 20 : firstType == 5;
        if (keyframe && !idr) throw invalid("Keyframe does not begin with an IDR");
        for (byte[] nal : filtered) {
            if (codec.isVideoNal(nal) && codec.isKeyNal(nal) != idr) {
                throw invalid("Mixed keyframe and inter-frame NALs");
            }
        }
        ByteBuffer output = ByteBuffer.allocate((int) length);
        for (byte[] nal : filtered) output.putInt(nal.length).put(nal);
        return output.array();
    }

    private static byte[] packet(int type, byte[] payload, int width, int height, long timestampUs) {
        validateDimensions(width, height);
        long timestamp = ntpTimestamp(timestampUs);
        if (payload.length > MAX_PAYLOAD_SIZE) throw invalid("Oversized media payload");
        ByteBuffer output = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0, payload.length).put(4, (byte) type).putLong(8, timestamp);
        output.putFloat(40, width).putFloat(44, height);
        output.position(HEADER_SIZE);
        output.put(payload);
        return output.array();
    }

    private static byte[] derive(byte[] seed, String label) {
        if (seed == null || seed.length != 32) throw invalid("Expected a 32-byte authenticated media seed");
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-512");
            hash.update(seed);
            return Arrays.copyOf(hash.digest(label.getBytes(StandardCharsets.US_ASCII)), 16);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-512 unavailable", error);
        }
    }

    private static byte[] encrypt(byte[] key, byte[] iv, byte[] input, int offset, int length) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input, offset, length);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Native media encryption failed", error);
        }
    }

    private static long ntpTimestamp(long timestampUs) {
        if (timestampUs < 0 || timestampUs / 1_000_000L > 0xffffffffL) throw invalid("Invalid NTP timestamp");
        return ((timestampUs / 1_000_000L) << 32) | (((timestampUs % 1_000_000L) << 32) / 1_000_000L);
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) throw invalid("Invalid video dimensions");
    }

    private static byte[] parameterSet(byte[] data, VideoCodec codec, int type) {
        if (data == null || data.length == 0 || data.length > 259) throw invalid("Invalid parameter set length");
        byte[] nal;
        if (hasStartCode(data)) {
            List<byte[]> nals = annexB(data);
            if (nals.size() != 1) throw invalid("Expected one parameter set");
            nal = nals.get(0);
        } else {
            for (int i = 0; i + 2 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                    throw invalid("Embedded Annex-B prefix in a raw parameter set");
                }
            }
            nal = data.clone();
        }
        validateNal(nal, codec);
        if (nal.length >= 256 || (codec == VideoCodec.H265 && nal.length < 3) || codec.nalType(nal) != type) {
            throw invalid("Invalid parameter set type or size");
        }
        return nal;
    }

    private static void validateParameterSize(byte[]... nals) {
        int length = 0;
        for (byte[] nal : nals) length += 4 + nal.length;
        if (length > 512) throw invalid("Parameter sets exceed the receiver configuration buffer");
    }

    private static boolean hasStartCode(byte[] data) {
        return data.length >= 3 && data[0] == 0 && data[1] == 0
                && (data[2] == 1 || (data.length >= 4 && data[2] == 0 && data[3] == 1));
    }

    private static List<byte[]> annexB(byte[] data) {
        if (!hasStartCode(data)) throw invalid("Expected an Annex-B access unit");
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
        if (nals.isEmpty() || prefixes != nals.size()) throw invalid("Empty Annex-B NAL");
        return nals;
    }

    private static void validateNal(byte[] nal, VideoCodec codec) {
        if (nal.length < (codec == VideoCodec.H265 ? 2 : 1) || (nal[0] & 0x80) != 0) {
            throw invalid("Invalid NAL header");
        }
        if (codec == VideoCodec.H265) {
            if ((nal[1] & 7) == 0 || (nal[0] & 1) != 0 || (nal[1] & 0xf8) != 0) {
                throw invalid("Unsupported HEVC layer or temporal ID");
            }
        } else if ((nal[0] & 31) == 0 || (nal[0] & 31) > 23) throw invalid("Invalid AVC NAL type");
    }

    private static void putHevcArray(ByteBuffer output, int type, byte[] nal) {
        output.put((byte) (0x80 | type)).putShort((short) 1).putShort((short) nal.length).put(nal);
    }

    private static HevcProfile hevcProfile(byte[] vps, byte[] sps) {
        Bits video = new Bits(rbsp(vps, 2));
        int videoId = video.read(4);
        video.read(2);
        if (video.read(6) != 0) throw invalid("Unsupported multilayer HEVC VPS");
        int videoSubLayers = video.read(3);
        if (videoSubLayers > 6) throw invalid("Invalid HEVC VPS layer count");
        int videoNested = video.read(1);
        if (video.read(16) != 0xffff) throw invalid("Invalid HEVC VPS reserved bits");
        byte[] videoTierLevel = new byte[12];
        for (int i = 0; i < videoTierLevel.length; i++) videoTierLevel[i] = (byte) video.read(8);
        Bits bits = new Bits(rbsp(sps, 2));
        if (bits.read(4) != videoId) throw invalid("HEVC SPS references a different VPS");
        int subLayers = bits.read(3);
        if (subLayers > 6) throw invalid("Invalid HEVC sub-layer count");
        int nested = bits.read(1);
        byte[] tierLevel = new byte[12];
        for (int i = 0; i < tierLevel.length; i++) tierLevel[i] = (byte) bits.read(8);
        if ((tierLevel[0] & 0xdf) != (videoTierLevel[0] & 0xdf)) {
            throw invalid("HEVC VPS and SPS use different profiles");
        }
        int sequenceTier = tierLevel[0] & 0x20, videoTier = videoTierLevel[0] & 0x20;
        if (videoTier > sequenceTier) tierLevel[11] = videoTierLevel[11];
        else if (videoTier == sequenceTier && (videoTierLevel[11] & 255) > (tierLevel[11] & 255)) {
            tierLevel[11] = videoTierLevel[11];
        }
        tierLevel[0] |= (byte) videoTier;
        for (int i = 1; i < 11; i++) tierLevel[i] &= videoTierLevel[i];
        boolean[] profileFlags = new boolean[subLayers], levelFlags = new boolean[subLayers];
        for (int i = 0; i < subLayers; i++) {
            profileFlags[i] = bits.read(1) != 0;
            levelFlags[i] = bits.read(1) != 0;
        }
        if (subLayers > 0) bits.skip((8 - subLayers) * 2);
        for (int i = 0; i < subLayers; i++) {
            if (profileFlags[i]) bits.skip(88);
            if (levelFlags[i]) bits.skip(8);
        }
        if (bits.ue() > 15) throw invalid("Invalid HEVC SPS identifier");
        int chroma = bits.ue();
        if (chroma > 3) throw invalid("Invalid HEVC chroma format");
        if (chroma == 3) bits.read(1);
        int width = bits.ue(), height = bits.ue();
        validateDimensions(width, height);
        if (bits.read(1) != 0) for (int i = 0; i < 4; i++) bits.ue();
        int luma = bits.ue(), chromaDepth = bits.ue();
        if (luma > 7 || chromaDepth > 7) throw invalid("Unsupported HEVC bit depth");
        return new HevcProfile(tierLevel, chroma, luma, chromaDepth,
                Math.max(subLayers, videoSubLayers) + 1, nested & videoNested);
    }

    private static byte[] rbsp(byte[] nal, int offset) {
        byte[] result = new byte[nal.length - offset];
        int length = 0, zeros = 0;
        for (int i = offset; i < nal.length; i++) {
            int value = nal[i] & 255;
            if (zeros >= 2 && value == 3) {
                if (i + 1 == nal.length || (nal[i + 1] & 255) > 3) throw invalid("Invalid NAL emulation prevention");
                zeros = 0;
                continue;
            }
            result[length++] = nal[i];
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return Arrays.copyOf(result, length);
    }

    private static final class HevcProfile {
        final byte[] tierLevel;
        final int chroma, lumaDepth, chromaDepth, layers, nested;
        HevcProfile(byte[] tierLevel, int chroma, int lumaDepth, int chromaDepth, int layers, int nested) {
            this.tierLevel = tierLevel;
            this.chroma = chroma;
            this.lumaDepth = lumaDepth;
            this.chromaDepth = chromaDepth;
            this.layers = layers;
            this.nested = nested;
        }
    }

    private static final class Bits {
        final byte[] bytes;
        int position;
        Bits(byte[] bytes) { this.bytes = bytes; }
        int read(int count) {
            if (count < 0 || count > 31 || position + count > bytes.length * 8) throw invalid("Truncated SPS");
            int value = 0;
            for (int i = 0; i < count; i++, position++) {
                value = (value << 1) | ((bytes[position >>> 3] >>> (7 - (position & 7))) & 1);
            }
            return value;
        }
        void skip(int count) {
            if (count < 0 || position + count > bytes.length * 8) throw invalid("Truncated SPS");
            position += count;
        }
        int ue() {
            int zeros = 0;
            while (read(1) == 0) if (++zeros > 30) throw invalid("Oversized SPS integer");
            return (int) ((1L << zeros) - 1 + read(zeros));
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
