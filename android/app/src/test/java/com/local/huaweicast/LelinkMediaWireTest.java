package com.local.huaweicast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class LelinkMediaWireTest {
    private static final byte[] SPS = {0x67, 0x42, 0, 0x1f, 12, 34};
    private static final byte[] PPS = {0x68, 56, 78};
    private static final byte[] VPS = hevcVps();
    private static final byte[] HEVC_PPS = {0x44, 1, (byte) 0xc0};
    private static final byte[] SEED = sequence(32);

    @Test public void avcRecordUsesNetworkLengthsAndCopiesParameters() {
        byte[] expected = {1, 0x42, 0, 0x1f, (byte) 0xff, (byte) 0xe1, 0, 6,
                0x67, 0x42, 0, 0x1f, 12, 34, 1, 0, 3, 0x68, 56, 78};
        assertArrayEquals(expected, LelinkMediaWire.avcConfiguration(SPS, PPS));
        byte[] sps = SPS.clone(), pps = PPS.clone();
        byte[] result = LelinkMediaWire.avcConfiguration(sps, pps);
        Arrays.fill(sps, (byte) 0);
        Arrays.fill(pps, (byte) 0);
        assertArrayEquals(expected, result);
        assertArrayEquals(expected, LelinkMediaWire.avcConfiguration(
                annexB(SPS), concat(new byte[]{0, 0, 1}, PPS)));
    }

    @Test public void configurationHeaderHasOnlyDocumentedFieldsAndClearPayload() {
        byte[] packet = LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS,
                960, 540, 1_500_000);
        assertHeader(packet, 1, 960, 540, 0x0000000180000000L);
        assertArrayEquals(LelinkMediaWire.avcConfiguration(SPS, PPS), payload(packet));
        assertEquals(128, LelinkMediaWire.HEADER_SIZE);
        assertEquals(3 * 1024 * 1024, LelinkMediaWire.MAX_PAYLOAD_SIZE);
    }

    @Test public void hevcConfigurationIsStandardHvc1WithOrderedHvccArrays() {
        byte[] sps = hevcSps(960, 540);
        byte[] configuration = LelinkMediaWire.hevcConfiguration(VPS, sps, HEVC_PPS, 960, 540);
        ByteBuffer fields = ByteBuffer.wrap(configuration);
        assertEquals(configuration.length, fields.getInt(0));
        assertEquals("hvc1", ascii(configuration, 4, 4));
        assertEquals(960, fields.getShort(32) & 0xffff);
        assertEquals(540, fields.getShort(34) & 0xffff);
        assertEquals(configuration.length - 86, fields.getInt(86));
        assertEquals("hvcC", ascii(configuration, 90, 4));
        assertEquals(1, configuration[94]);
        assertEquals(1, configuration[95]);
        assertEquals(0x60000000, fields.getInt(96));
        assertArrayEquals(new byte[]{(byte) 0x90, 0, 0, 0, 0, 0},
                Arrays.copyOfRange(configuration, 100, 106));
        assertEquals(120, configuration[106] & 0xff);
        assertEquals(1, configuration[110] & 3);
        assertEquals(0, configuration[111] & 7);
        assertEquals(0, configuration[112] & 7);
        assertEquals(3, configuration[115] & 3);
        assertEquals(3, configuration[116]);
        int offset = 117;
        byte[][] nals = {VPS, sps, HEVC_PPS};
        for (int index = 0; index < nals.length; index++) {
            assertEquals(32 + index, configuration[offset] & 0x3f);
            assertEquals(1, fields.getShort(offset + 1) & 0xffff);
            assertEquals(nals[index].length, fields.getShort(offset + 3) & 0xffff);
            assertArrayEquals(nals[index], Arrays.copyOfRange(configuration,
                    offset + 5, offset + 5 + nals[index].length));
            offset += 5 + nals[index].length;
        }
        assertEquals(configuration.length, offset);
        byte[] packet = LelinkMediaWire.codecPacket(VideoCodec.H265, VPS, sps, HEVC_PPS,
                960, 540, 2_250_000);
        assertHeader(packet, 1, 960, 540, 0x0000000240000000L);
        assertArrayEquals(configuration, payload(packet));
    }

    @Test public void hevcConfigurationAcceptsAnnexBWithoutChangingHvcc() {
        byte[] sps = hevcSps(1920, 1080);
        assertArrayEquals(LelinkMediaWire.hevcConfiguration(VPS, sps, HEVC_PPS, 1920, 1080),
                LelinkMediaWire.hevcConfiguration(annexB(VPS), concat(new byte[]{0, 0, 1}, sps),
                        annexB(HEVC_PPS), 1920, 1080));
    }

    @Test public void hevcMetadataCombinesCompatibilityAndConstraintsWithBitwiseAnd() {
        byte[] vps = hevcVps(profileTierLevel(1, 90, 0x68000001, 0xb00000000001L), 0, true);
        byte[] sps = hevcSps(960, 540, profileTierLevel(1, 120, 0x60000101, 0x980000000003L),
                0, true, 0, 0, 0, 0);
        byte[] configuration = LelinkMediaWire.hevcConfiguration(vps, sps, HEVC_PPS, 960, 540);
        assertEquals(0x60000001, ByteBuffer.wrap(configuration).getInt(96));
        assertArrayEquals(new byte[]{(byte) 0x90, 0, 0, 0, 0, 1},
                Arrays.copyOfRange(configuration, 100, 106));
        assertEquals(120, configuration[106] & 0xff);
    }

    @Test public void hevcTierTakesPrecedenceOverLevelAndEqualTiersKeepHighestLevel() {
        int[][] cases = {
                {1, 153, 1, 120, 1, 153},
                {1, 90, 1, 120, 1, 120},
                {0x21, 90, 1, 153, 0x21, 90},
                {1, 153, 0x21, 120, 0x21, 120},
                {0x21, 153, 0x21, 120, 0x21, 153}
        };
        for (int[] values : cases) {
            byte[] vps = hevcVps(profileTierLevel(values[0], values[1], 0x60000000, 0x900000000000L), 0, true);
            byte[] sps = hevcSps(960, 540,
                    profileTierLevel(values[2], values[3], 0x60000000, 0x900000000000L),
                    0, true, 0, 0, 0, 0);
            byte[] configuration = LelinkMediaWire.hevcConfiguration(vps, sps, HEVC_PPS, 960, 540);
            assertEquals(values[4], configuration[95] & 0xff);
            assertEquals(values[5], configuration[106] & 0xff);
        }
    }

    @Test public void hevcMain10PreservesBothTenBitDepthFields() {
        byte[] ptl = profileTierLevel(2, 120, 0x20000000, 0x900000000000L);
        byte[] sps = hevcSps(1920, 1080, ptl, 0, true, 2, 2, 0, 0);
        byte[] configuration = LelinkMediaWire.hevcConfiguration(hevcVps(ptl, 0, true),
                sps, HEVC_PPS, 1920, 1080);
        assertEquals(2, configuration[95] & 31);
        assertEquals(1, configuration[110] & 3);
        assertEquals(2, configuration[111] & 7);
        assertEquals(2, configuration[112] & 7);
        assertEquals(0xf8, configuration[111] & 0xf8);
        assertEquals(0xf8, configuration[112] & 0xf8);
    }

    @Test public void hevcSubLayerPresenceFlagsSkipEachOptionalProfileAndLevel() {
        byte[] ptl = profileTierLevel(2, 120, 0x20000000, 0x900000000000L);
        byte[] vps = hevcVps(ptl, 5, false);
        byte[] sps = hevcSps(960, 540, ptl, 3, true, 2, 1, 0b101, 0b110);
        byte[] configuration = LelinkMediaWire.hevcConfiguration(vps, sps, HEVC_PPS, 960, 540);
        assertEquals(6, (configuration[115] >>> 3) & 7);
        assertEquals(0, (configuration[115] >>> 2) & 1);
        assertEquals(1, configuration[110] & 3);
        assertEquals(2, configuration[111] & 7);
        assertEquals(1, configuration[112] & 7);
        byte[] nested = LelinkMediaWire.hevcConfiguration(hevcVps(ptl, 1, true),
                sps, HEVC_PPS, 960, 540);
        assertEquals(4, (nested[115] >>> 3) & 7);
        assertEquals(1, (nested[115] >>> 2) & 1);
    }

    @Test public void invalidHevcEmulationPreventionAndTruncatedTierLevelAreRejected() {
        byte[] sps = hevcSps(960, 540);
        for (byte[] suffix : new byte[][]{new byte[]{0, 0, 3}, new byte[]{0, 0, 3, 4}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> LelinkMediaWire.hevcConfiguration(concat(VPS, suffix), sps, HEVC_PPS, 960, 540));
            assertThrows(IllegalArgumentException.class,
                    () -> LelinkMediaWire.hevcConfiguration(VPS, concat(sps, suffix), HEVC_PPS, 960, 540));
        }
        for (int length = 3; length < 18; length++) {
            byte[] truncatedVps = Arrays.copyOf(VPS, length);
            assertThrows(IllegalArgumentException.class,
                    () -> LelinkMediaWire.hevcConfiguration(truncatedVps, sps, HEVC_PPS, 960, 540));
        }
        for (int length = 3; length < 15; length++) {
            byte[] truncatedSps = Arrays.copyOf(sps, length);
            assertThrows(IllegalArgumentException.class,
                    () -> LelinkMediaWire.hevcConfiguration(VPS, truncatedSps, HEVC_PPS, 960, 540));
        }
    }

    @Test public void videoEncryptionMatchesIndependentJcaReferenceAndLeavesOuterBytesClear() throws Exception {
        byte[] nal = avcNal(5, 129);
        byte[] clear = lengthPrefixed(nal);
        byte[] expected = referenceVideo(clear, derived("VIDEO-KEY"), derived("VIDEO-IV"));
        byte[] input = annexB(nal), original = input.clone();
        byte[] packet = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                .videoPacket(input, true, 1920, 1080, 1_500_000);
        assertHeader(packet, 0, 1920, 1080, 0x0000000180000000L);
        assertArrayEquals(expected, payload(packet));
        assertArrayEquals(original, input);
        assertArrayEquals(Arrays.copyOf(clear, 5), Arrays.copyOf(expected, 5));
        assertFalse(Arrays.equals(Arrays.copyOfRange(clear, 5, 69),
                Arrays.copyOfRange(expected, 5, 69)));
        assertArrayEquals(Arrays.copyOfRange(clear, 69, clear.length),
                Arrays.copyOfRange(expected, 69, expected.length));
    }

    @Test public void videoIvContinuesAcrossIdrsButNotInterFramesOrConfiguration() throws Exception {
        byte[] firstClear = lengthPrefixed(avcNal(5, 129));
        byte[] secondClear = lengthPrefixed(avcNal(5, 161));
        byte[] firstEncrypted = referenceVideo(firstClear, derived("VIDEO-KEY"), derived("VIDEO-IV"));
        int encryptedLength = ((firstClear.length - 5) >>> 1) & ~15;
        byte[] nextIv = Arrays.copyOfRange(firstEncrypted, 5 + encryptedLength - 16, 5 + encryptedLength);
        LelinkMediaWire.VideoEncryptor encryptor = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264);
        assertArrayEquals(firstEncrypted, payload(encryptor.videoPacket(
                annexB(avcNal(5, 129)), true, 960, 540, 0)));
        byte[] interFrame = avcNal(1, 117);
        assertArrayEquals(lengthPrefixed(interFrame), payload(encryptor.videoPacket(
                annexB(interFrame), false, 960, 540, 100_000)));
        assertArrayEquals(LelinkMediaWire.avcConfiguration(SPS, PPS), payload(
                LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 960, 540, 150_000)));
        assertArrayEquals(referenceVideo(secondClear, derived("VIDEO-KEY"), nextIv),
                payload(encryptor.videoPacket(annexB(avcNal(5, 161)), true, 960, 540, 200_000)));
    }

    @Test public void shortKeyframeDoesNotAdvanceCipherIv() throws Exception {
        LelinkMediaWire.VideoEncryptor encryptor = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264);
        byte[] shortNal = avcNal(5, 16);
        assertArrayEquals(lengthPrefixed(shortNal), payload(encryptor.videoPacket(
                annexB(shortNal), true, 960, 540, 0)));
        byte[] nal = avcNal(5, 129);
        assertArrayEquals(referenceVideo(lengthPrefixed(nal), derived("VIDEO-KEY"), derived("VIDEO-IV")),
                payload(encryptor.videoPacket(annexB(nal), true, 960, 540, 1)));
    }

    @Test public void actualNalTypeDeterminesEncryptionEvenWithoutKeyframeFlag() {
        byte[] avc = annexB(avcNal(5, 129));
        assertArrayEquals(new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                        .videoPacket(avc, true, 960, 540, 0),
                new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                        .videoPacket(avc, false, 960, 540, 0));
        for (int type : new int[]{19, 20}) {
            byte[] hevc = annexB(hevcNal(type, 130));
            assertArrayEquals(new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H265)
                            .videoPacket(hevc, true, 960, 540, 0),
                    new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H265)
                            .videoPacket(hevc, false, 960, 540, 0));
        }
    }

    @Test public void avcFilteringRemovesParameterSetsAudAndSeiBeforeEncryption() throws Exception {
        byte[] idr = avcNal(5, 129);
        byte[] input = annexB(SPS, PPS, new byte[]{9, 0x10}, new byte[]{6, 0x20}, idr);
        byte[] expected = referenceVideo(lengthPrefixed(idr), derived("VIDEO-KEY"), derived("VIDEO-IV"));
        assertArrayEquals(expected, payload(new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                .videoPacket(input, true, 960, 540, 0)));
    }

    @Test public void hevcFilteringRemovesParameterSetsAudAndPrefixSeiWithoutLegacyAudInsertion() throws Exception {
        byte[] idr = hevcNal(19, 130);
        byte[] input = annexB(VPS, hevcSps(960, 540), HEVC_PPS,
                new byte[]{0x46, 1, 0x50}, new byte[]{0x4e, 1, 0x20}, idr);
        byte[] clear = lengthPrefixed(idr);
        byte[] packet = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H265)
                .videoPacket(input, true, 960, 540, 0);
        assertArrayEquals(referenceVideo(clear, derived("VIDEO-KEY"), derived("VIDEO-IV")), payload(packet));
        assertEquals(idr.length, ByteBuffer.wrap(payload(packet)).getInt(0));
        assertEquals(19, (payload(packet)[4] & 0x7e) >>> 1);
    }

    @Test public void interFramesPreserveMultipleNalLengthsAndRemainClear() {
        byte[] first = avcNal(1, 17), second = avcNal(1, 19);
        byte[] input = concat(annexB(first), concat(new byte[]{0, 0, 1}, second));
        assertArrayEquals(lengthPrefixed(first, second), payload(
                new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                        .videoPacket(input, false, 960, 540, 0)));
        byte[] hevc = hevcNal(1, 18);
        assertArrayEquals(lengthPrefixed(hevc), payload(
                new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H265)
                        .videoPacket(annexB(hevc), false, 960, 540, 0)));
    }

    @Test public void audioMatchesIndependentCbcWithClearTailAndResetsIvPerPacket() throws Exception {
        byte[] raw = sequence(47), original = raw.clone();
        byte[] expected = raw.clone();
        byte[] encrypted = cbc(Arrays.copyOf(raw, 32), derived("AUDIO-KEY"), derived("AUDIO-IV"));
        System.arraycopy(encrypted, 0, expected, 0, encrypted.length);
        LelinkMediaWire.AudioEncryptor encryptor = new LelinkMediaWire.AudioEncryptor(SEED);
        byte[] result = encryptor.encryptPayload(raw);
        assertArrayEquals(expected, result);
        assertArrayEquals(original, raw);
        assertArrayEquals(original, concat(cbcDecrypt(Arrays.copyOf(result, 32),
                derived("AUDIO-KEY"), derived("AUDIO-IV")), Arrays.copyOfRange(result, 32, result.length)));
        assertArrayEquals(expected, encryptor.encryptPayload(raw));
        assertArrayEquals(Arrays.copyOfRange(raw, 32, 47), Arrays.copyOfRange(result, 32, 47));
        result[0] ^= 1;
        assertArrayEquals(expected, encryptor.encryptPayload(raw));
    }

    @Test public void shortAudioPacketsAreCopiedWithoutPadding() {
        byte[] raw = sequence(15);
        byte[] result = new LelinkMediaWire.AudioEncryptor(SEED).encryptPayload(raw);
        assertArrayEquals(raw, result);
        assertNotSame(raw, result);
        result[0] ^= 1;
        assertEquals(0, raw[0]);
    }

    @Test public void constructorsDefensivelyCopyTheSeedAndKeepMediaKeysSeparate() {
        byte[] seed = SEED.clone();
        LelinkMediaWire.VideoEncryptor video = new LelinkMediaWire.VideoEncryptor(seed, VideoCodec.H264);
        LelinkMediaWire.AudioEncryptor audio = new LelinkMediaWire.AudioEncryptor(seed);
        Arrays.fill(seed, (byte) 0);
        byte[] frame = annexB(avcNal(5, 129)), raw = sequence(32);
        assertArrayEquals(new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                        .videoPacket(frame, true, 960, 540, 0),
                video.videoPacket(frame, true, 960, 540, 0));
        assertArrayEquals(new LelinkMediaWire.AudioEncryptor(SEED).encryptPayload(raw),
                audio.encryptPayload(raw));
    }

    @Test public void wrongKeyframeFlagsUnsupportedCraAndInvalidFramesAreRejectedWithoutIvAdvance() {
        LelinkMediaWire.VideoEncryptor avc = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264);
        assertThrows(IllegalArgumentException.class, () -> avc.videoPacket(
                annexB(avcNal(1, 129)), true, 960, 540, 0));
        for (byte[] malformed : new byte[][]{null, new byte[0], new byte[]{0x65, 1},
                new byte[]{0, 0, 1}, new byte[]{0, 0, 1, (byte) 0xe5, 1},
                new byte[]{0, 0, 1, 0, 0, 1, 0x65, 1},
                new byte[]{0, 0, 1, 0x65, 1, 0, 0, 1}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> avc.videoPacket(malformed, true, 960, 540, 0));
        }
        byte[] valid = annexB(avcNal(5, 129));
        assertArrayEquals(new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                        .videoPacket(valid, true, 960, 540, 0),
                avc.videoPacket(valid, true, 960, 540, 0));
        LelinkMediaWire.VideoEncryptor hevc = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H265);
        for (boolean keyframe : new boolean[]{false, true}) {
            assertThrows(IllegalArgumentException.class,
                    () -> hevc.videoPacket(annexB(hevcNal(21, 130)), keyframe, 960, 540, 0));
        }
        for (byte[] malformed : new byte[][]{new byte[]{0x26}, new byte[]{(byte) 0xa6, 1},
                new byte[]{0x26, 0}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> hevc.videoPacket(annexB(malformed), true, 960, 540, 0));
        }
    }

    @Test public void parameterSetsEnforceNalKindsMinimumLengthAndOneByteReceiverLengths() {
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[]{0x67},
                new byte[]{0x67, 0x42, 0}, PPS, concat(SPS, annexB(SPS)),
                filledNal(0x67, 256)}) {
            assertThrows(IllegalArgumentException.class, () -> LelinkMediaWire.avcConfiguration(invalid, PPS));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.avcConfiguration(SPS, new byte[]{0x68}));
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.avcConfiguration(SPS, filledNal(0x68, 256)));
        byte[] hevcSps = hevcSps(960, 540);
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.hevcConfiguration(HEVC_PPS, hevcSps, VPS, 960, 540));
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.hevcConfiguration(VPS, new byte[]{0x42, 1, 0}, HEVC_PPS, 960, 540));
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.hevcConfiguration(VPS, hevcSps, new byte[]{0x44, 0}, 960, 540));
    }

    @Test public void expandedParameterSetBufferAllowsExactly512Bytes() {
        byte[] sps = filledNal(0x67, 255), pps = filledNal(0x68, 249);
        sps[1] = 0x42;
        sps[2] = 0;
        sps[3] = 0x1f;
        assertEquals(512, sps.length + pps.length + 8);
        assertEquals(sps.length + pps.length + 11, LelinkMediaWire.avcConfiguration(sps, pps).length);
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.avcConfiguration(sps, filledNal(0x68, 250)));
    }

    @Test public void payloadLimitCountsNetworkNalLengthPrefixes() {
        byte[] nal = avcNal(1, LelinkMediaWire.MAX_PAYLOAD_SIZE - 4);
        LelinkMediaWire.VideoEncryptor encryptor = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264);
        assertEquals(LelinkMediaWire.HEADER_SIZE + LelinkMediaWire.MAX_PAYLOAD_SIZE,
                encryptor.videoPacket(annexB(nal), false, 960, 540, 0).length);
        byte[] oversized = avcNal(1, LelinkMediaWire.MAX_PAYLOAD_SIZE - 3);
        assertThrows(IllegalArgumentException.class,
                () -> encryptor.videoPacket(annexB(oversized), false, 960, 540, 0));
    }

    @Test public void dimensionsAndUnsignedNtpRangeAreValidatedBeforeCipherStateChanges() {
        LelinkMediaWire.VideoEncryptor encryptor = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264);
        byte[] frame = annexB(avcNal(5, 129));
        for (int dimension : new int[]{-1, 0, 16385}) {
            assertThrows(IllegalArgumentException.class,
                    () -> encryptor.videoPacket(frame, true, dimension, 540, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> encryptor.videoPacket(frame, true, 960, dimension, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, dimension, 540, 0));
        }
        for (long timestamp : new long[]{-1, 0x100000000L * 1_000_000, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> encryptor.videoPacket(frame, true, 960, 540, timestamp));
            assertThrows(IllegalArgumentException.class,
                    () -> LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 960, 540, timestamp));
        }
        assertArrayEquals(new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264)
                        .videoPacket(frame, true, 960, 540, 0),
                encryptor.videoPacket(frame, true, 960, 540, 0));
        assertHeader(LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS,
                16384, 1, 0xffffffffL * 1_000_000 + 500_000), 1, 16384, 1, 0xffffffff80000000L);
    }

    @Test public void constructorsRejectInvalidSeedsAndNullCodecs() {
        for (byte[] seed : new byte[][]{null, new byte[0], new byte[31], new byte[33]}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LelinkMediaWire.VideoEncryptor(seed, VideoCodec.H264));
            assertThrows(IllegalArgumentException.class, () -> new LelinkMediaWire.AudioEncryptor(seed));
        }
        assertThrows(IllegalArgumentException.class, () -> new LelinkMediaWire.VideoEncryptor(SEED, null));
        assertThrows(IllegalArgumentException.class,
                () -> LelinkMediaWire.codecPacket(null, null, SPS, PPS, 960, 540, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LelinkMediaWire.AudioEncryptor(SEED).encryptPayload(null));
    }

    @Test public void closedEncryptorsCannotEmitMorePacketsAndCloseIsIdempotent() {
        LelinkMediaWire.VideoEncryptor video = new LelinkMediaWire.VideoEncryptor(SEED, VideoCodec.H264);
        LelinkMediaWire.AudioEncryptor audio = new LelinkMediaWire.AudioEncryptor(SEED);
        video.close();
        audio.close();
        assertThrows(IllegalStateException.class,
                () -> video.videoPacket(annexB(avcNal(5, 129)), true, 960, 540, 0));
        assertThrows(IllegalStateException.class, () -> audio.encryptPayload(sequence(32)));
        video.close();
        audio.close();
    }

    private static void assertHeader(byte[] packet, int type, int width, int height, long timestamp) {
        ByteBuffer expected = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        expected.putInt(0, packet.length - 128);
        expected.put(4, (byte) type);
        expected.putLong(8, timestamp);
        expected.putFloat(40, width);
        expected.putFloat(44, height);
        assertArrayEquals(expected.array(), Arrays.copyOf(packet, 128));
    }

    private static byte[] payload(byte[] packet) {
        return Arrays.copyOfRange(packet, 128, packet.length);
    }

    private static byte[] derived(String purpose) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        digest.update(SEED);
        digest.update(("LELINK-MIRROR-" + purpose).getBytes(StandardCharsets.US_ASCII));
        return Arrays.copyOf(digest.digest(), 16);
    }

    private static byte[] referenceVideo(byte[] clear, byte[] key, byte[] iv) throws Exception {
        byte[] expected = clear.clone();
        int count = ((clear.length - 5) >>> 1) & ~15;
        if (count > 0) System.arraycopy(cbc(Arrays.copyOfRange(clear, 5, 5 + count), key, iv), 0, expected, 5, count);
        return expected;
    }

    private static byte[] cbc(byte[] data, byte[] key, byte[] iv) throws Exception {
        return crypt(Cipher.ENCRYPT_MODE, data, key, iv);
    }

    private static byte[] cbcDecrypt(byte[] data, byte[] key, byte[] iv) throws Exception {
        return crypt(Cipher.DECRYPT_MODE, data, key, iv);
    }

    private static byte[] crypt(int mode, byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static byte[] sequence(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < result.length; index++) result[index] = (byte) index;
        return result;
    }

    private static byte[] filledNal(int header, int length) {
        byte[] nal = new byte[length];
        Arrays.fill(nal, (byte) 0x55);
        nal[0] = (byte) header;
        return nal;
    }

    private static byte[] avcNal(int type, int length) {
        return filledNal(0x60 | type, length);
    }

    private static byte[] hevcNal(int type, int length) {
        byte[] nal = filledNal(type << 1, length);
        nal[1] = 1;
        return nal;
    }

    private static byte[] annexB(byte[]... nals) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            result.write(0); result.write(0); result.write(0); result.write(1);
            result.write(nal, 0, nal.length);
        }
        return result.toByteArray();
    }

    private static byte[] lengthPrefixed(byte[]... nals) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            byte[] length = ByteBuffer.allocate(4).putInt(nal.length).array();
            result.write(length, 0, length.length);
            result.write(nal, 0, nal.length);
        }
        return result.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static byte[] hevcVps() {
        return hevcVps(profileTierLevel(1, 120, 0x60000000, 0x900000000000L), 0, true);
    }

    private static byte[] hevcVps(byte[] ptl, int subLayers, boolean nested) {
        Bits bits = new Bits();
        bits.put(0, 4); bits.put(1, 1); bits.put(1, 1); bits.put(0, 6);
        bits.put(subLayers, 3); bits.put(nested ? 1 : 0, 1); bits.put(0xffff, 16);
        putProfileTierLevel(bits, ptl, subLayers, 0, 0);
        bits.put(1, 1);
        return escapedNal(0x40, bits.bytes());
    }

    private static byte[] hevcSps(int width, int height) {
        return hevcSps(width, height, profileTierLevel(1, 120, 0x60000000, 0x900000000000L),
                0, true, 0, 0, 0, 0);
    }

    private static byte[] hevcSps(int width, int height, byte[] ptl, int subLayers, boolean nested,
                                int lumaDepth, int chromaDepth, int profileMask, int levelMask) {
        Bits bits = new Bits();
        bits.put(0, 4); bits.put(subLayers, 3); bits.put(nested ? 1 : 0, 1);
        putProfileTierLevel(bits, ptl, subLayers, profileMask, levelMask);
        bits.ue(0); bits.ue(1); bits.ue(width); bits.ue(height);
        bits.put(0, 1); bits.ue(lumaDepth); bits.ue(chromaDepth); bits.put(1, 1);
        return escapedNal(0x42, bits.bytes());
    }

    private static byte[] profileTierLevel(int profileAndTier, int level, int compatibility, long constraints) {
        ByteBuffer result = ByteBuffer.allocate(12);
        result.put((byte) profileAndTier).putInt(compatibility);
        for (int index = 5; index >= 0; index--) result.put((byte) (constraints >>> (index * 8)));
        return result.put((byte) level).array();
    }

    private static void putProfileTierLevel(Bits bits, byte[] ptl, int subLayers, int profileMask, int levelMask) {
        for (byte value : ptl) bits.put(value & 0xff, 8);
        for (int index = 0; index < subLayers; index++) {
            bits.put((profileMask >>> index) & 1, 1);
            bits.put((levelMask >>> index) & 1, 1);
        }
        if (subLayers > 0) bits.put(0, (8 - subLayers) * 2);
        for (int index = 0; index < subLayers; index++) {
            if ((profileMask & (1 << index)) != 0) {
                for (int field = 0; field < 11; field++) bits.put(0x80 + field + index, 8);
            }
            if ((levelMask & (1 << index)) != 0) bits.put(0x70 + index, 8);
        }
    }

    private static byte[] escapedNal(int header, byte[] rbsp) {
        ByteArrayOutputStream escaped = new ByteArrayOutputStream();
        escaped.write(header); escaped.write(1);
        int zeros = 0;
        for (byte value : rbsp) {
            if (zeros == 2 && (value & 0xff) <= 3) {
                escaped.write(3);
                zeros = 0;
            }
            escaped.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return escaped.toByteArray();
    }

    private static final class Bits {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private int pending, count;

        void put(long value, int length) {
            for (int index = length - 1; index >= 0; index--) {
                pending = (pending << 1) | (int) ((value >>> index) & 1);
                if (++count == 8) {
                    data.write(pending);
                    count = 0;
                    pending = 0;
                }
            }
        }

        void ue(int value) {
            int encoded = value + 1;
            int length = 32 - Integer.numberOfLeadingZeros(encoded);
            put(0, length - 1);
            put(encoded, length);
        }

        byte[] bytes() {
            if (count > 0) put(0, 8 - count);
            return data.toByteArray();
        }
    }
}
