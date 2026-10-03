package com.local.huaweicast;

import org.bouncycastle.crypto.engines.ChaChaEngine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Native Lelink type-1 control records, not RFC 8439 ChaCha20-Poly1305 AEAD.
 * Keys and nonces must come from a fresh authenticated session and must not be reused across sessions.
 */
public final class LelinkSecureRecord {
    public static final int MAX_PLAINTEXT_SIZE = 32 * 1024;
    private static final int KEY_SIZE = 32;
    private static final int NONCE_SIZE = 8;
    private static final int HEADER_SIZE = 4;
    private static final int TAG_SIZE = 16;
    private static final int RECORD_KEY_STREAM_SIZE = 64;

    private LelinkSecureRecord() {}

    /** One cipher stream with native per-record block consumption for an outbound direction. */
    public static final class Encoder implements AutoCloseable {
        private final RecordState state;

        public Encoder(byte[] key, byte[] nonce) {
            state = new RecordState(key, nonce);
        }

        /** Empty records are valid; invalid caller input does not consume the cipher stream. */
        public synchronized byte[] encode(byte[] plaintext) {
            state.requireOpen();
            if (plaintext == null || plaintext.length > MAX_PLAINTEXT_SIZE) {
                throw new IllegalArgumentException("Invalid Lelink plaintext length");
            }
            byte[] snapshot = plaintext.clone();
            byte[] record = new byte[HEADER_SIZE + snapshot.length + TAG_SIZE];
            byte[] recordKey = new byte[RECORD_KEY_STREAM_SIZE];
            byte[] tag = new byte[TAG_SIZE];
            boolean complete = false;
            try {
                writeLength(record, snapshot.length);
                state.nextRecordKey(recordKey);
                state.cipher.processBytes(snapshot, 0, snapshot.length, record, HEADER_SIZE);
                state.discardPayloadTail(snapshot.length);
                authenticate(recordKey, snapshot, tag);
                System.arraycopy(tag, 0, record, HEADER_SIZE + snapshot.length, TAG_SIZE);
                complete = true;
                return record;
            } finally {
                Arrays.fill(snapshot, (byte) 0);
                Arrays.fill(recordKey, (byte) 0);
                Arrays.fill(tag, (byte) 0);
                if (!complete) {
                    Arrays.fill(record, (byte) 0);
                    state.close();
                }
            }
        }

        @Override public synchronized void close() {
            state.close();
        }
    }

    /** One inbound cipher stream; any read or authentication failure is terminal. */
    public static final class Decoder implements AutoCloseable {
        private final RecordState state;

        public Decoder(byte[] key, byte[] nonce) {
            state = new RecordState(key, nonce);
        }

        /**
         * Reads exactly one bounded record and returns plaintext only after authenticating it.
         * EOF, even between records, closes this decoder. The caller owns the input stream.
         */
        public synchronized byte[] read(InputStream input) throws IOException {
            if (state.closed) throw new IOException("Lelink record decoder is closed");
            byte[] header = new byte[HEADER_SIZE];
            byte[] ciphertext = null;
            byte[] plaintext = null;
            byte[] receivedTag = new byte[TAG_SIZE];
            byte[] expectedTag = new byte[TAG_SIZE];
            byte[] recordKey = new byte[RECORD_KEY_STREAM_SIZE];
            boolean complete = false;
            try {
                if (input == null) throw new IllegalArgumentException("Missing Lelink record input");
                readFully(input, header);
                long length = (header[0] & 0xffL) | ((header[1] & 0xffL) << 8)
                        | ((header[2] & 0xffL) << 16) | ((header[3] & 0xffL) << 24);
                if (length > MAX_PLAINTEXT_SIZE) {
                    throw new IOException("Lelink record exceeds size limit");
                }
                ciphertext = new byte[(int) length];
                readFully(input, ciphertext);
                readFully(input, receivedTag);
                plaintext = new byte[(int) length];
                state.nextRecordKey(recordKey);
                state.cipher.processBytes(ciphertext, 0, ciphertext.length, plaintext, 0);
                state.discardPayloadTail(ciphertext.length);
                authenticate(recordKey, plaintext, expectedTag);
                if (!org.bouncycastle.util.Arrays.constantTimeAreEqual(receivedTag, expectedTag)) {
                    throw new IOException("Lelink record authentication failed");
                }
                byte[] result = plaintext;
                plaintext = null;
                complete = true;
                return result;
            } finally {
                Arrays.fill(header, (byte) 0);
                if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
                Arrays.fill(receivedTag, (byte) 0);
                Arrays.fill(expectedTag, (byte) 0);
                Arrays.fill(recordKey, (byte) 0);
                if (!complete) state.close();
            }
        }

        @Override public synchronized void close() {
            state.close();
        }
    }

    private static final class RecordState {
        private WipingChaCha20 cipher;
        private boolean closed;

        RecordState(byte[] key, byte[] nonce) {
            if (key == null || key.length != KEY_SIZE || nonce == null || nonce.length != NONCE_SIZE) {
                throw new IllegalArgumentException("Lelink records require a 32-byte key and 8-byte nonce");
            }
            cipher = new WipingChaCha20();
            initialize(cipher, key, nonce);
        }

        void requireOpen() {
            if (closed) throw new IllegalStateException("Lelink record encoder is closed");
        }

        void nextRecordKey(byte[] block) {
            cipher.processBytes(block, 0, block.length, block, 0);
        }

        void discardPayloadTail(int length) {
            // The native cipher consumes whole blocks per call and never reuses a partial block.
            int padding = (RECORD_KEY_STREAM_SIZE - length % RECORD_KEY_STREAM_SIZE) % RECORD_KEY_STREAM_SIZE;
            if (padding != 0) cipher.skip(padding);
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                cipher.destroy();
            } finally {
                cipher = null;
            }
        }
    }

    private static final class WipingChaCha20 extends ChaChaEngine {
        private boolean destroyed;

        WipingChaCha20() { super(20); }

        void destroy() {
            destroyed = true;
            Arrays.fill(engineState, 0);
            Arrays.fill(x, 0);
            // BC reset invokes generateKeyStream with its private buffer, allowing it to be wiped.
            reset();
        }

        @Override protected void generateKeyStream(byte[] output) {
            if (destroyed) Arrays.fill(output, (byte) 0);
            else super.generateKeyStream(output);
        }
    }

    private static void initialize(ChaChaEngine cipher, byte[] key, byte[] nonce) {
        KeyParameter keyParameter = new KeyParameter(key);
        ParametersWithIV parameters = new ParametersWithIV(keyParameter, nonce);
        try {
            cipher.init(true, parameters);
        } finally {
            Arrays.fill(keyParameter.getKey(), (byte) 0);
            Arrays.fill(parameters.getIV(), (byte) 0);
        }
    }

    private static void authenticate(byte[] recordKey, byte[] plaintext, byte[] tag) {
        Poly1305 mac = new Poly1305();
        KeyParameter keyParameter = new KeyParameter(recordKey, 0, KEY_SIZE);
        byte[] zeroBlock = new byte[TAG_SIZE];
        byte[] discardedTag = new byte[TAG_SIZE];
        try {
            mac.init(keyParameter);
            mac.update(plaintext, 0, plaintext.length);
            mac.doFinal(tag, 0);
        } finally {
            Arrays.fill(keyParameter.getKey(), (byte) 0);
            // Wipe the primitive's key limbs and retained partial plaintext block before release.
            mac.init(keyParameter);
            mac.update(zeroBlock, 0, zeroBlock.length);
            mac.doFinal(discardedTag, 0);
            Arrays.fill(discardedTag, (byte) 0);
        }
    }

    private static void writeLength(byte[] output, int length) {
        for (int index = 0; index < HEADER_SIZE; index++) {
            output[index] = (byte) (length >>> (index * 8));
        }
    }

    private static void readFully(InputStream input, byte[] output) throws IOException {
        int offset = 0;
        while (offset < output.length) {
            int count = input.read(output, offset, output.length - offset);
            if (count < 0) throw new EOFException("Truncated Lelink record");
            if (count == 0) {
                int next = input.read();
                if (next < 0) throw new EOFException("Truncated Lelink record");
                output[offset++] = (byte) next;
            } else {
                offset += count;
            }
        }
    }
}
