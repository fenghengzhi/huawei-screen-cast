package com.local.huaweicast;

import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.bouncycastle.crypto.engines.Salsa20Engine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class LelinkSecureRecordTest {
    private static final byte[] KEY = sequence(32, 0x31);
    private static final byte[] NONCE = sequence(8, 0x71);

    @Test public void matchesFixedOriginalChaCha20AndPlaintextPoly1305GoldenVector() throws Exception {
        byte[] plaintext = "native Lelink control".getBytes(StandardCharsets.US_ASCII);
        byte[] expected = hex("15000000f16693d723341836fdd6fe12180d6b62a57b5bcf24"
                + "0dfb9667d68d0170a7d60ca30f414800");
        try (LelinkSecureRecord.Encoder encoder = new LelinkSecureRecord.Encoder(new byte[32], new byte[8]);
             LelinkSecureRecord.Decoder decoder = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
            assertArrayEquals(expected, encoder.encode(plaintext));
            assertArrayEquals(plaintext, decoder.read(new ByteArrayInputStream(expected)));
        }
    }

    @Test public void emptyRecordHasARealAuthenticationTagAndConsumes64StreamBytes() throws Exception {
        byte[] expected = hex("00000000bdd219b8a08ded1aa836efcc8b770dc7");
        try (LelinkSecureRecord.Encoder encoder = new LelinkSecureRecord.Encoder(new byte[32], new byte[8]);
             LelinkSecureRecord.Decoder decoder = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
            byte[] first = encoder.encode(new byte[0]);
            byte[] second = encoder.encode(new byte[0]);
            assertArrayEquals(expected, first);
            assertFalse(Arrays.equals(first, second));
            assertArrayEquals(new byte[0], decoder.read(new ByteArrayInputStream(first)));
            assertArrayEquals(new byte[0], decoder.read(new ByteArrayInputStream(second)));
        }
    }

    @Test public void consecutiveRecordsMatchNativeWholeBlockConsumption() throws Exception {
        byte[][] plaintexts = {sequence(1, 2), sequence(63, 3), sequence(65, 4), new byte[0], sequence(129, 5)};
        byte[][] expected = referenceRecords(KEY, NONCE, plaintexts);
        try (LelinkSecureRecord.Encoder encoder = encoder(); LelinkSecureRecord.Decoder decoder = decoder()) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int index = 0; index < plaintexts.length; index++) {
                byte[] actual = encoder.encode(plaintexts[index]);
                assertArrayEquals(expected[index], actual);
                all.write(actual);
            }
            InputStream input = new ByteArrayInputStream(all.toByteArray());
            for (byte[] plaintext : plaintexts) assertArrayEquals(plaintext, decoder.read(input));
            assertEquals(-1, input.read());
        }
    }

    @Test public void matchesFixedMultiRecordVectorIncludingPartialAndEmptyPayloads() throws Exception {
        byte[][] plaintexts = {
                "native Lelink control".getBytes(StandardCharsets.US_ASCII),
                "second".getBytes(StandardCharsets.US_ASCII), new byte[0], new byte[]{1}
        };
        byte[][] expected = {
                hex("15000000f16693d723341836fdd6fe12180d6b62a57b5bcf24"
                        + "0dfb9667d68d0170a7d60ca30f414800"),
                hex("060000006045c337b9d7806d21a43a76a4f474ff5464c3bd1df9"),
                hex("00000000e51ee03b1ca9f82aca173edb8b729347"),
                hex("01000000f3034286ff51f14ff3d768e2acaca12b09")
        };
        try (LelinkSecureRecord.Encoder encoder = new LelinkSecureRecord.Encoder(new byte[32], new byte[8]);
             LelinkSecureRecord.Decoder decoder = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
            for (int index = 0; index < plaintexts.length; index++) {
                assertArrayEquals(expected[index], encoder.encode(plaintexts[index]));
                assertArrayEquals(plaintexts[index], decoder.read(new ByteArrayInputStream(expected[index])));
            }
        }
    }

    @Test public void rejectsByteContinuousSecondRecordAfterNativePartialBlockWasDiscarded() throws Exception {
        byte[][] plaintexts = {sequence(7, 2), sequence(11, 3)};
        byte[][] byteContinuous = referenceRecords(KEY, NONCE, plaintexts, false);
        byte[][] nativeAligned = referenceRecords(KEY, NONCE, plaintexts);
        assertArrayEquals(nativeAligned[0], byteContinuous[0]);
        assertFalse(Arrays.equals(nativeAligned[1], byteContinuous[1]));
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            assertArrayEquals(plaintexts[0], decoder.read(new ByteArrayInputStream(byteContinuous[0])));
            assertAuthenticationFailure(decoder, byteContinuous[1]);
            assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(nativeAligned[1])));
        }
    }

    @Test public void discardsEveryPartialBlockTailButNeverAddsPaddingAfterEmptyOrAlignedPayloads() throws Exception {
        byte[][] plaintexts = new byte[132][];
        for (int length = 0; length <= 129; length++) plaintexts[length] = sequence(length, length);
        plaintexts[130] = sequence(LelinkSecureRecord.MAX_PLAINTEXT_SIZE, 11);
        plaintexts[131] = new byte[0];
        byte[][] expected = referenceRecords(KEY, NONCE, plaintexts);
        try (LelinkSecureRecord.Encoder encoder = encoder(); LelinkSecureRecord.Decoder decoder = decoder()) {
            for (int index = 0; index < plaintexts.length; index++) {
                assertArrayEquals(expected[index], encoder.encode(plaintexts[index]));
                assertArrayEquals(plaintexts[index], decoder.read(new ByteArrayInputStream(expected[index])));
            }
        }
    }

    @Test public void decryptsFragmentedHeadersBodiesAndTagsWithoutReadingFollowingRecord() throws Exception {
        byte[] first = sequence(257, 1);
        byte[] second = sequence(11, 2);
        byte[][] records = referenceRecords(KEY, NONCE, new byte[][]{first, second});
        byte[] both = concatenate(records);
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            ByteArrayInputStream raw = new ByteArrayInputStream(both);
            InputStream fragmented = new InputStream() {
                @Override public int read() { return raw.read(); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    return raw.read(bytes, offset, Math.min(length, 1));
                }
            };
            assertArrayEquals(first, decoder.read(fragmented));
            assertEquals(records[1].length, raw.available());
            assertArrayEquals(second, decoder.read(fragmented));
            assertEquals(0, raw.available());
        }
    }

    @Test public void makesProgressWhenAnInputStreamReturnsZeroFromBulkRead() throws Exception {
        byte[] plaintext = sequence(7, 3);
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{plaintext})[0];
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            ByteArrayInputStream raw = new ByteArrayInputStream(record);
            InputStream zeroBulkReads = new InputStream() {
                @Override public int read() { return raw.read(); }
                @Override public int read(byte[] bytes, int offset, int length) { return 0; }
            };
            assertArrayEquals(plaintext, decoder.read(zeroBulkReads));
        }
    }

    @Test public void rejectsWrongKeyAndNonceBeforeReturningAnyPlaintext() throws Exception {
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{sequence(80, 4)})[0];
        byte[] wrongKey = KEY.clone();
        byte[] wrongNonce = NONCE.clone();
        wrongKey[7] ^= 1;
        wrongNonce[3] ^= 1;
        try (LelinkSecureRecord.Decoder wrongKeyDecoder = new LelinkSecureRecord.Decoder(wrongKey, NONCE);
             LelinkSecureRecord.Decoder wrongNonceDecoder = new LelinkSecureRecord.Decoder(KEY, wrongNonce)) {
            assertAuthenticationFailure(wrongKeyDecoder, record);
            assertAuthenticationFailure(wrongNonceDecoder, record);
        }
    }

    @Test public void detectsEverySingleBytePayloadOrTagCorruptionAndCannotResume() throws Exception {
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{sequence(21, 4)})[0];
        for (int index = 4; index < record.length; index++) {
            byte[] modified = record.clone();
            modified[index] ^= 1;
            try (LelinkSecureRecord.Decoder decoder = decoder()) {
                assertAuthenticationFailure(decoder, modified);
                assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(record)));
            }
        }
    }

    @Test public void rejectsMacOverCiphertextInsteadOfPlaintext() throws Exception {
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{sequence(33, 8)})[0];
        ChaChaEngine cipher = referenceCipher(KEY, NONCE);
        byte[] keyStream = new byte[64];
        cipher.processBytes(keyStream, 0, keyStream.length, keyStream, 0);
        Poly1305 wrongMac = new Poly1305();
        wrongMac.init(new KeyParameter(keyStream, 0, 32));
        wrongMac.update(record, 4, 33);
        wrongMac.doFinal(record, 4 + 33);
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            assertAuthenticationFailure(decoder, record);
        }
    }

    @Test public void rejectsAllTruncationPointsAndCleanEofIsAlsoTerminal() throws Exception {
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{sequence(17, 3)})[0];
        for (int length = 0; length < record.length; length++) {
            byte[] partial = Arrays.copyOf(record, length);
            try (LelinkSecureRecord.Decoder decoder = decoder()) {
                assertThrows(EOFException.class, () -> decoder.read(new ByteArrayInputStream(partial)));
                assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(record)));
            }
        }
    }

    @Test public void rejectsOversizeAndUnsignedLengthsWithoutReadingAnyBody() throws Exception {
        for (int length : new int[]{32769, 0x7fffffff, 0x80000000, 0xffffffff}) {
            byte[] header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(length).array();
            ByteArrayInputStream raw = new ByteArrayInputStream(header);
            InputStream input = new InputStream() {
                @Override public int read() {
                    if (raw.available() == 0) fail("Oversize record attempted to read a body");
                    return raw.read();
                }
            };
            try (LelinkSecureRecord.Decoder decoder = decoder()) {
                assertThrows(IOException.class, () -> decoder.read(input));
                assertEquals(0, raw.available());
                assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(new byte[20])));
            }
        }
    }

    @Test public void alteredShorterLengthFailsAuthenticationRatherThanReturningAPrefix() throws Exception {
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{sequence(7, 3)})[0];
        record[0] = 6;
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            assertAuthenticationFailure(decoder, record);
        }
    }

    @Test public void alteredLongerLengthCannotConsumeTheNextRecordAndResume() throws Exception {
        byte[][] records = referenceRecords(KEY, NONCE, new byte[][]{sequence(7, 3), sequence(8, 4)});
        byte[] all = concatenate(records);
        all[0] = 8;
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            ByteArrayInputStream input = new ByteArrayInputStream(all);
            assertThrows(IOException.class, () -> decoder.read(input));
            assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(records[1])));
        }
    }

    @Test public void preservesTheStreamOnRejectedEncoderCallerInput() throws Exception {
        byte[] plaintext = sequence(11, 7);
        try (LelinkSecureRecord.Encoder encoder = encoder()) {
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(null));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new byte[LelinkSecureRecord.MAX_PLAINTEXT_SIZE + 1]));
            assertArrayEquals(referenceRecords(KEY, NONCE, new byte[][]{plaintext})[0], encoder.encode(plaintext));
        }
    }

    @Test public void acceptsMaximumPlaintextAndEncodesFullLittleEndianLength() throws Exception {
        byte[] plaintext = sequence(LelinkSecureRecord.MAX_PLAINTEXT_SIZE, 0x26);
        try (LelinkSecureRecord.Encoder encoder = encoder(); LelinkSecureRecord.Decoder decoder = decoder()) {
            byte[] record = encoder.encode(plaintext);
            assertEquals(32788, record.length);
            assertArrayEquals(new byte[]{0, (byte) 0x80, 0, 0}, Arrays.copyOf(record, 4));
            assertArrayEquals(plaintext, decoder.read(new ByteArrayInputStream(record)));
        }
    }

    @Test public void requiresExactly32ByteKeysAnd8ByteOriginalChaChaNonces() {
        for (byte[] key : new byte[][]{null, new byte[0], new byte[16], new byte[31], new byte[33]}) {
            assertThrows(IllegalArgumentException.class, () -> new LelinkSecureRecord.Encoder(key, NONCE));
            assertThrows(IllegalArgumentException.class, () -> new LelinkSecureRecord.Decoder(key, NONCE));
        }
        for (byte[] nonce : new byte[][]{null, new byte[0], new byte[7], new byte[9], new byte[12], new byte[16]}) {
            assertThrows(IllegalArgumentException.class, () -> new LelinkSecureRecord.Encoder(KEY, nonce));
            assertThrows(IllegalArgumentException.class, () -> new LelinkSecureRecord.Decoder(KEY, nonce));
        }
    }

    @Test public void callerKeyAndNonceChangesCannotAlterInitializedStreams() throws Exception {
        byte[] key = KEY.clone();
        byte[] nonce = NONCE.clone();
        byte[] plaintext = sequence(81, 3);
        byte[] originalPlaintext = plaintext.clone();
        try (LelinkSecureRecord.Encoder encoder = new LelinkSecureRecord.Encoder(key, nonce);
             LelinkSecureRecord.Decoder decoder = new LelinkSecureRecord.Decoder(key, nonce)) {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            byte[] record = encoder.encode(plaintext);
            assertArrayEquals(originalPlaintext, plaintext);
            assertArrayEquals(referenceRecords(KEY, NONCE, new byte[][]{plaintext})[0], record);
            byte[] decoded = decoder.read(new ByteArrayInputStream(record));
            Arrays.fill(record, (byte) 0);
            assertArrayEquals(plaintext, decoded);
        }
    }

    @Test public void twoDirectionsHaveIndependentOffsets() throws Exception {
        byte[] outbound1 = sequence(13, 1);
        byte[] outbound2 = sequence(37, 2);
        byte[] inbound1 = sequence(99, 3);
        try (LelinkSecureRecord.Encoder outbound = encoder(); LelinkSecureRecord.Decoder inbound = decoder();
             LelinkSecureRecord.Encoder remoteOutbound = encoder(); LelinkSecureRecord.Decoder remoteInbound = decoder()) {
            assertArrayEquals(outbound1, remoteInbound.read(new ByteArrayInputStream(outbound.encode(outbound1))));
            assertArrayEquals(inbound1, inbound.read(new ByteArrayInputStream(remoteOutbound.encode(inbound1))));
            assertArrayEquals(outbound2, remoteInbound.read(new ByteArrayInputStream(outbound.encode(outbound2))));
        }
    }

    @Test public void connectionReadExceptionIsTerminalWithoutPlaintextFallback() throws Exception {
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            InputStream broken = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("disconnected"); }
            };
            IOException failure = assertThrows(IOException.class, () -> decoder.read(broken));
            assertEquals("disconnected", failure.getMessage());
            assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(new byte[20])));
        }
    }

    @Test public void nullInputIsTerminalAndClosingDoesNotOwnTheUnderlyingStream() throws Exception {
        LelinkSecureRecord.Decoder invalid = decoder();
        assertThrows(IllegalArgumentException.class, () -> invalid.read(null));
        assertThrows(IOException.class, () -> invalid.read(new ByteArrayInputStream(new byte[20])));
        byte[] record = referenceRecords(KEY, NONCE, new byte[][]{new byte[0]})[0];
        boolean[] closed = {false};
        ByteArrayInputStream input = new ByteArrayInputStream(record) {
            @Override public void close() { closed[0] = true; }
        };
        try (LelinkSecureRecord.Decoder decoder = decoder()) {
            assertArrayEquals(new byte[0], decoder.read(input));
        }
        assertFalse(closed[0]);
    }

    @Test public void closeIsIdempotentAndPreventsFurtherEncryptionOrDecryption() throws Exception {
        LelinkSecureRecord.Encoder encoder = encoder();
        LelinkSecureRecord.Decoder decoder = decoder();
        encoder.close();
        encoder.close();
        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, () -> encoder.encode(new byte[0]));
        assertThrows(IOException.class, () -> decoder.read(new ByteArrayInputStream(new byte[20])));
    }

    @Test public void closeAndAuthenticationFailureOverwriteAndReleaseCipherState() throws Exception {
        LelinkSecureRecord.Encoder encoder = encoder();
        Object encoderState = field(encoder, "state");
        Object encoderCipher = field(encoderState, "cipher");
        encoder.encode(sequence(15, 2));
        encoder.close();
        assertWiped(encoderState, encoderCipher);

        LelinkSecureRecord.Decoder decoder = decoder();
        Object decoderState = field(decoder, "state");
        Object decoderCipher = field(decoderState, "cipher");
        byte[] bad = referenceRecords(KEY, NONCE, new byte[][]{sequence(15, 2)})[0];
        bad[bad.length - 1] ^= 1;
        assertAuthenticationFailure(decoder, bad);
        assertWiped(decoderState, decoderCipher);
    }

    private static void assertWiped(Object state, Object cipher) throws Exception {
        assertNull(field(state, "cipher"));
        for (String name : new String[]{"engineState", "x"}) {
            Field field = Salsa20Engine.class.getDeclaredField(name);
            field.setAccessible(true);
            assertArrayEquals(new int[16], (int[]) field.get(cipher));
        }
        Field keyStream = Salsa20Engine.class.getDeclaredField("keyStream");
        keyStream.setAccessible(true);
        assertArrayEquals(new byte[64], (byte[]) keyStream.get(cipher));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertAuthenticationFailure(LelinkSecureRecord.Decoder decoder, byte[] record) {
        IOException failure = assertThrows(IOException.class,
                () -> decoder.read(new ByteArrayInputStream(record)));
        assertEquals("Lelink record authentication failed", failure.getMessage());
    }

    private static LelinkSecureRecord.Encoder encoder() { return new LelinkSecureRecord.Encoder(KEY, NONCE); }
    private static LelinkSecureRecord.Decoder decoder() { return new LelinkSecureRecord.Decoder(KEY, NONCE); }

    private static byte[][] referenceRecords(byte[] key, byte[] nonce, byte[][] plaintexts) {
        return referenceRecords(key, nonce, plaintexts, true);
    }

    private static byte[][] referenceRecords(byte[] key, byte[] nonce, byte[][] plaintexts,
                                              boolean consumeWholeBlocks) {
        int size = 0;
        for (byte[] plaintext : plaintexts) {
            size += 64 + (consumeWholeBlocks ? ((plaintext.length + 63) / 64) * 64 : plaintext.length);
        }
        byte[] stream = new byte[size];
        referenceCipher(key, nonce).processBytes(stream, 0, stream.length, stream, 0);
        byte[][] result = new byte[plaintexts.length][];
        int streamOffset = 0;
        for (int index = 0; index < plaintexts.length; index++) {
            byte[] plaintext = plaintexts[index];
            byte[] record = ByteBuffer.allocate(4 + plaintext.length + 16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(plaintext.length).array();
            Poly1305 mac = new Poly1305();
            mac.init(new KeyParameter(stream, streamOffset, 32));
            mac.update(plaintext, 0, plaintext.length);
            mac.doFinal(record, 4 + plaintext.length);
            streamOffset += 64;
            for (int offset = 0; offset < plaintext.length; offset++) {
                record[4 + offset] = (byte) (plaintext[offset] ^ stream[streamOffset + offset]);
            }
            streamOffset += consumeWholeBlocks ? ((plaintext.length + 63) / 64) * 64 : plaintext.length;
            result[index] = record;
        }
        return result;
    }

    private static ChaChaEngine referenceCipher(byte[] key, byte[] nonce) {
        ChaChaEngine cipher = new ChaChaEngine(20);
        cipher.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
        return cipher;
    }

    private static byte[] concatenate(byte[][] arrays) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) output.write(array);
        return output.toByteArray();
    }

    private static byte[] sequence(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) value[index] = (byte) (seed + index);
        return value;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
