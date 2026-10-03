package com.local.huaweicast;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Independent, one-use FREE/type-1 pairing for a receiver offering that mode.
 * No network, persistent identity, password fallback, or media authorization is implied.
 * The exchanged self-generated public keys are not an out-of-band receiver identity check.
 */
public final class LelinkFreePairing implements AutoCloseable {
    private enum State { NEW, SETUP_SENT, VERIFY_SENT, FINISH_SENT, COMPLETE, FAILED, CLOSED }

    private static final String SIGNATURE_KEY = "LELINK-VERIFY_SIGNATURE-KEY";
    private static final String SIGNATURE_IV = "LELINK-VERIFY-SIGNATURE-NONCE";
    private static final String IDENTITY_KEY = "LELINK-VERIFY_IDENTITY-KEY";
    // This spelling is part of the receiver's protocol, not a corrected product name.
    private static final String IDENTITY_IV = "LEINK-VERIFY-IDENTITY-NONCE";

    private State state = State.NEW;
    private final byte[] signingSeed = new byte[32];
    private final byte[] clientNonce = new byte[32];
    private byte[] signingPublic;
    private byte[] exchangePrivate;
    private byte[] exchangePublic;
    private byte[] serverSigningPublic;
    private byte[] serverExchangePublic;
    private byte[] serverNonce;
    private byte[] sharedSecret;

    public LelinkFreePairing() {
        SecureRandom random = new SecureRandom();
        random.nextBytes(signingSeed);
        random.nextBytes(clientNonce);
        signingPublic = new Ed25519PrivateKeyParameters(signingSeed, 0).generatePublicKey().getEncoded();
        X25519PrivateKeyParameters exchange = new X25519PrivateKeyParameters(random);
        exchangePrivate = exchange.getEncoded();
        exchangePublic = exchange.generatePublicKey().getEncoded();
    }

    /** M1 body for POST /lelink-setup. A new instance is required for each connection. */
    public synchronized byte[] initialRequest() {
        requireState(State.NEW);
        byte[] result = LelinkAuthWire.encode(List.of(
                field(0, new byte[]{0, 1, 0, 1}), field(1, new byte[]{1}),
                field(2, new byte[]{1}), field(3, join(signingPublic, clientNonce))));
        state = State.SETUP_SENT;
        return result;
    }

    /** Validates M2 and returns the M3 body for POST /lelink-verify. */
    public synchronized byte[] acceptSetupReturnVerify(byte[] response) throws PairingException {
        requireState(State.SETUP_SENT);
        try {
            byte[] identity = responsePayload(response, 2, 3, 64);
            serverSigningPublic = Arrays.copyOfRange(identity, 0, 32);
            serverNonce = Arrays.copyOfRange(identity, 32, 64);
            byte[] result = message(3, 4, join(exchangePublic, signingPublic));
            state = State.VERIFY_SENT;
            return result;
        } catch (RuntimeException failure) {
            throw fail("Invalid FREE setup response", failure);
        }
    }

    /** Authenticates M4 and returns the M5 body for POST /lelink-verify. */
    public synchronized byte[] acceptVerifyReturnFinish(byte[] response) throws PairingException {
        requireState(State.VERIFY_SENT);
        byte[] serverSignature = null;
        byte[] clientSignature = null;
        try {
            byte[] exchange = responsePayload(response, 4, 4, 96);
            serverExchangePublic = Arrays.copyOfRange(exchange, 0, 32);
            sharedSecret = new byte[32];
            new X25519PrivateKeyParameters(exchangePrivate, 0).generateSecret(
                    new X25519PublicKeyParameters(serverExchangePublic, 0), sharedSecret, 0);
            if (MessageDigest.isEqual(sharedSecret, new byte[32])) {
                throw new IllegalArgumentException("Invalid X25519 peer key");
            }
            serverSignature = crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(exchange, 32, 96),
                    SIGNATURE_KEY, SIGNATURE_IV);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, new Ed25519PublicKeyParameters(serverSigningPublic, 0));
            byte[] signedServer = join(serverExchangePublic, exchangePublic);
            verifier.update(signedServer, 0, signedServer.length);
            if (!verifier.verifySignature(serverSignature)) {
                throw new IllegalArgumentException("Invalid receiver signature");
            }

            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, new Ed25519PrivateKeyParameters(signingSeed, 0));
            byte[] signedClient = join(exchangePublic, serverExchangePublic);
            signer.update(signedClient, 0, signedClient.length);
            clientSignature = signer.generateSignature();
            byte[] result = message(5, 5, crypt(Cipher.ENCRYPT_MODE, clientSignature,
                    IDENTITY_KEY, IDENTITY_IV));
            state = State.FINISH_SENT;
            return result;
        } catch (RuntimeException | GeneralSecurityException failure) {
            throw fail("Invalid FREE verification response", failure);
        } finally {
            wipe(serverSignature);
            wipe(clientSignature);
        }
    }

    /** Control secrets are released only after the M6 nonce confirmation succeeds. */
    public synchronized ControlSecrets acceptFinish(byte[] response) throws PairingException {
        requireState(State.FINISH_SENT);
        byte[] hash = null;
        byte[] key = null;
        byte[] nonce = null;
        try {
            byte[] echoedNonce = responsePayload(response, 6, 5, 32);
            if (!MessageDigest.isEqual(serverNonce, echoedNonce)) {
                throw new IllegalArgumentException("Invalid receiver nonce confirmation");
            }
            hash = hash32(clientNonce, serverNonce);
            for (int i = 0; i < 2; i++) {
                byte[] next = hash32(sharedSecret, hash);
                wipe(hash);
                hash = next;
            }
            key = hash32(hash, ascii("LELINK-IDENTITY-KEY"));
            nonce = hash32(hash, ascii("LELINK-IDENTITY-NONCE"));
            ControlSecrets result = new ControlSecrets(key, nonce, hash);
            state = State.COMPLETE;
            eraseEphemeral();
            return result;
        } catch (RuntimeException | GeneralSecurityException failure) {
            throw fail("Invalid FREE completion response", failure);
        } finally {
            wipe(hash);
            wipe(key);
            wipe(nonce);
        }
    }

    /** Erases arrays owned by this session; JCA/BC may hold temporary internal copies. */
    @Override public synchronized void close() {
        eraseEphemeral();
        state = State.CLOSED;
    }

    public static final class ControlSecrets implements AutoCloseable {
        private final byte[] key;
        private final byte[] nonce;
        private final byte[] mediaSeed;
        private boolean closed;

        private ControlSecrets(byte[] key, byte[] nonce, byte[] mediaSeed) {
            this.key = key.clone();
            this.nonce = nonce.clone();
            this.mediaSeed = mediaSeed.clone();
        }

        public synchronized byte[] key() { ensureOpen(); return key.clone(); }
        public synchronized byte[] nonce() { ensureOpen(); return nonce.clone(); }
        public synchronized byte[] mediaSeed() { ensureOpen(); return mediaSeed.clone(); }

        @Override public synchronized void close() {
            wipe(key);
            wipe(nonce);
            wipe(mediaSeed);
            closed = true;
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Pairing secrets have been closed");
        }
    }

    public static final class PairingException extends Exception {
        private PairingException(String message, Throwable cause) { super(message, cause); }
    }

    private void requireState(State expected) {
        if (state == expected) return;
        State actual = state;
        eraseEphemeral();
        state = State.FAILED;
        throw new IllegalStateException("Pairing expected " + expected + " but was " + actual);
    }

    private PairingException fail(String message, Exception cause) {
        eraseEphemeral();
        state = State.FAILED;
        return new PairingException(message, cause);
    }

    private static byte[] responsePayload(byte[] response, int stage, int payloadTag, int size) {
        List<LelinkAuthWire.Field> fields = LelinkAuthWire.decode(response);
        if (fields.size() != 3) throw new IllegalArgumentException("Unexpected pairing field count");
        byte[] payload = null;
        boolean validType = false;
        boolean validStage = false;
        for (LelinkAuthWire.Field field : fields) {
            byte[] value = field.value();
            if (field.tag() == 1) {
                validType = value.length == 1 && value[0] == 1;
            } else if (field.tag() == 2) {
                validStage = value.length == 1 && value[0] == stage;
            } else if (field.tag() == payloadTag && value.length == size) {
                payload = value;
            } else {
                throw new IllegalArgumentException("Unexpected pairing field or length");
            }
        }
        if (!validType || !validStage || payload == null) {
            throw new IllegalArgumentException("Unexpected pairing type, stage, or payload");
        }
        return payload;
    }

    private byte[] crypt(int mode, byte[] value, String keyLabel, String ivLabel)
            throws GeneralSecurityException {
        byte[] key = null;
        byte[] iv = null;
        try {
            key = hash16(ascii(keyLabel), sharedSecret);
            iv = hash16(ascii(ivLabel), sharedSecret);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(value);
        } finally {
            wipe(key);
            wipe(iv);
        }
    }

    private static byte[] hash16(byte[] first, byte[] second) throws GeneralSecurityException {
        return hash(first, second, 16);
    }

    private static byte[] hash32(byte[] first, byte[] second) throws GeneralSecurityException {
        return hash(first, second, 32);
    }

    private static byte[] hash(byte[] first, byte[] second, int size) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        digest.update(first);
        byte[] full = digest.digest(second);
        byte[] result = Arrays.copyOf(full, size);
        wipe(full);
        return result;
    }

    private static byte[] message(int stage, int payloadTag, byte[] payload) {
        return LelinkAuthWire.encode(List.of(field(1, new byte[]{1}),
                field(2, new byte[]{(byte) stage}), field(payloadTag, payload)));
    }

    private static LelinkAuthWire.Field field(int tag, byte[] value) {
        return new LelinkAuthWire.Field(tag, value);
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }

    private void eraseEphemeral() {
        wipe(signingSeed);
        wipe(clientNonce);
        wipe(signingPublic);
        wipe(exchangePrivate);
        wipe(exchangePublic);
        wipe(serverSigningPublic);
        wipe(serverExchangePublic);
        wipe(serverNonce);
        wipe(sharedSecret);
    }
}
