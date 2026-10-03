package com.local.huaweicast;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class LelinkFreePairingTest {
    @Test public void setupUsesExactFreeTypeOneFieldsAndFreshIdentity() {
        try (LelinkFreePairing client = new LelinkFreePairing()) {
            List<LelinkAuthWire.Field> fields = LelinkAuthWire.decode(client.initialRequest());
            assertEquals(4, fields.size());
            assertEquals(0, fields.get(0).tag());
            assertArrayEquals(new byte[]{0, 1, 0, 1}, fields.get(0).value());
            assertEquals(1, fields.get(1).tag());
            assertArrayEquals(new byte[]{1}, fields.get(1).value());
            assertEquals(2, fields.get(2).tag());
            assertArrayEquals(new byte[]{1}, fields.get(2).value());
            assertEquals(3, fields.get(3).tag());
            assertEquals(64, fields.get(3).value().length);
        }
    }

    @Test public void identitiesNoncesAndExchangeKeysAreFreshForEverySession() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            assertFalse(Arrays.equals(first.peer.clientSigning, second.peer.clientSigning));
            assertFalse(Arrays.equals(first.peer.clientNonce, second.peer.clientNonce));
            byte[] a = payload(first.client.acceptSetupReturnVerify(first.peer.setup()), 4);
            byte[] b = payload(second.client.acceptSetupReturnVerify(second.peer.setup()), 4);
            assertFalse(Arrays.equals(Arrays.copyOf(a, 32), Arrays.copyOf(b, 32)));
        }
    }

    @Test public void verifyContainsExchangeKeyAndTheSameSigningIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] verify = fixture.client.acceptSetupReturnVerify(fixture.peer.setup());
            assertMessage(verify, 3, 4, 64);
            assertArrayEquals(fixture.peer.clientSigning, Arrays.copyOfRange(payload(verify, 4), 32, 64));
        }
    }

    @Test public void completesMutuallySignedExchangeAndIndependentControlKdf() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] verify = fixture.client.acceptSetupReturnVerify(fixture.peer.setup());
            byte[] finish = fixture.client.acceptVerifyReturnFinish(fixture.peer.verify(verify));
            assertMessage(finish, 5, 5, 64);
            fixture.peer.verifyClientFinish(finish);
            LelinkFreePairing.ControlSecrets secrets = fixture.client.acceptFinish(fixture.peer.finish());
            assertArrayEquals(fixture.peer.control("LELINK-IDENTITY-KEY"), secrets.key());
            assertArrayEquals(fixture.peer.control("LELINK-IDENTITY-NONCE"), secrets.nonce());
            assertArrayEquals(fixture.peer.mediaSeed(), secrets.mediaSeed());
            assertEquals(32, secrets.key().length);
            assertEquals(32, secrets.nonce().length);
            assertFalse(Arrays.equals(secrets.key(), secrets.nonce()));
        }
    }

    @Test public void controlSecretsAreDefensiveAndSurviveSessionClose() throws Exception {
        Fixture fixture = new Fixture();
        LelinkFreePairing.ControlSecrets secrets = fixture.complete();
        byte[] expectedKey = secrets.key();
        byte[] expectedNonce = secrets.nonce();
        byte[] expectedSeed = secrets.mediaSeed();
        Arrays.fill(secrets.key(), (byte) 0);
        Arrays.fill(secrets.nonce(), (byte) 0);
        Arrays.fill(secrets.mediaSeed(), (byte) 0);
        fixture.close();
        assertArrayEquals(expectedKey, secrets.key());
        assertArrayEquals(expectedNonce, secrets.nonce());
        assertArrayEquals(expectedSeed, secrets.mediaSeed());
        secrets.close();
    }

    @Test public void closingSecretsIsIdempotentAndPreventsFurtherCopies() throws Exception {
        try (Fixture fixture = new Fixture()) {
            LelinkFreePairing.ControlSecrets secrets = fixture.complete();
            secrets.close();
            secrets.close();
            assertThrows(IllegalStateException.class, secrets::key);
            assertThrows(IllegalStateException.class, secrets::nonce);
            assertThrows(IllegalStateException.class, secrets::mediaSeed);
        }
    }

    @Test public void receiverFieldsMayBeReorderedButNotDuplicated() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] verify = fixture.client.acceptSetupReturnVerify(reversed(fixture.peer.setup()));
            byte[] finish = fixture.client.acceptVerifyReturnFinish(reversed(fixture.peer.verify(verify)));
            fixture.peer.verifyClientFinish(finish);
            assertNotNull(fixture.client.acceptFinish(reversed(fixture.peer.finish())));
        }
    }

    @Test public void callersCannotMutatePreviouslyReturnedSetupOrVerifyState() throws Exception {
        try (LelinkFreePairing client = new LelinkFreePairing()) {
            byte[] setup = client.initialRequest();
            Peer peer = new Peer(setup);
            Arrays.fill(setup, (byte) 0);
            byte[] response = peer.setup();
            byte[] verify = client.acceptSetupReturnVerify(response);
            Arrays.fill(response, (byte) 0);
            byte[] reply = peer.verify(verify);
            Arrays.fill(verify, (byte) 0);
            byte[] finish = client.acceptVerifyReturnFinish(reply);
            Arrays.fill(reply, (byte) 0);
            peer.verifyClientFinish(finish);
            assertArrayEquals(peer.control("LELINK-IDENTITY-KEY"), client.acceptFinish(peer.finish()).key());
        }
    }

    @Test public void initialRequestCannotBeResentOnTheSameStateMachine() {
        try (LelinkFreePairing client = new LelinkFreePairing()) {
            client.initialRequest();
            assertThrows(IllegalStateException.class, client::initialRequest);
            assertThrows(IllegalStateException.class, () -> client.acceptSetupReturnVerify(new byte[0]));
        }
    }

    @Test public void noReplyCanBeAcceptedBeforeInitialRequest() {
        for (int stage : new int[]{2, 4, 6}) {
            try (LelinkFreePairing client = new LelinkFreePairing()) {
                assertThrows(IllegalStateException.class, () -> accept(client, stage, new byte[0]));
                assertThrows(IllegalStateException.class, client::initialRequest);
            }
        }
    }

    @Test public void skippingAStagePermanentlyInvalidatesTheSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.client.acceptVerifyReturnFinish(new byte[0]));
            assertThrows(IllegalStateException.class,
                    () -> fixture.client.acceptSetupReturnVerify(fixture.peer.setup()));
        }
        try (Fixture fixture = new Fixture()) {
            fixture.client.acceptSetupReturnVerify(fixture.peer.setup());
            assertThrows(IllegalStateException.class, () -> fixture.client.acceptFinish(fixture.peer.finish()));
            assertThrows(IllegalStateException.class, () -> fixture.client.acceptVerifyReturnFinish(new byte[0]));
        }
    }

    @Test public void completedSessionCannotReleaseSecretsAgainOrRestart() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.complete();
            assertThrows(IllegalStateException.class, () -> fixture.client.acceptFinish(fixture.peer.finish()));
            assertThrows(IllegalStateException.class, fixture.client::initialRequest);
        }
    }

    @Test public void closeIsIdempotentAndEveryStageRejectsFurtherWork() throws Exception {
        for (int before : new int[]{0, 2, 4, 6, 8}) {
            LelinkFreePairing client = new LelinkFreePairing();
            Peer peer = null;
            if (before > 0) peer = new Peer(client.initialRequest());
            byte[] verify = before >= 4 ? client.acceptSetupReturnVerify(peer.setup()) : null;
            if (before >= 6) client.acceptVerifyReturnFinish(peer.verify(verify));
            if (before >= 8) client.acceptFinish(peer.finish());
            client.close();
            client.close();
            assertThrows(IllegalStateException.class, client::initialRequest);
            assertThrows(IllegalStateException.class, () -> client.acceptSetupReturnVerify(new byte[0]));
            assertThrows(IllegalStateException.class, () -> client.acceptVerifyReturnFinish(new byte[0]));
            assertThrows(IllegalStateException.class, () -> client.acceptFinish(new byte[0]));
        }
    }

    @Test public void rejectsWrongTypesAtEveryResponseWithoutDowngrading() throws Exception {
        for (int stage : new int[]{2, 4, 6}) {
            for (int type : new int[]{0, 2, 3, 255}) {
                try (Fixture fixture = new Fixture()) {
                    byte[] valid = fixture.responseFor(stage);
                    assertRejected(fixture, stage, replace(valid, 1, new byte[]{(byte) type}));
                }
            }
        }
    }

    @Test public void rejectsWrongStagesAtEveryResponse() throws Exception {
        for (int stage : new int[]{2, 4, 6}) {
            for (int wrong : new int[]{0, 1, 3, 5, 7, 255}) {
                try (Fixture fixture = new Fixture()) {
                    byte[] valid = fixture.responseFor(stage);
                    assertRejected(fixture, stage, replace(valid, 2, new byte[]{(byte) wrong}));
                }
            }
        }
    }

    @Test public void rejectsMissingAndMultiByteTypeOrStage() throws Exception {
        for (int stage : new int[]{2, 4, 6}) {
            for (int tag : new int[]{1, 2}) {
                for (byte[] invalid : new byte[][]{new byte[0], new byte[]{1, 0}, new byte[]{2, 0, 0, 0}}) {
                    try (Fixture fixture = new Fixture()) {
                        assertRejected(fixture, stage, replace(fixture.responseFor(stage), tag, invalid));
                    }
                }
                try (Fixture fixture = new Fixture()) {
                    List<LelinkAuthWire.Field> fields = new ArrayList<>(
                            LelinkAuthWire.decode(fixture.responseFor(stage)));
                    fields.removeIf(field -> field.tag() == tag);
                    assertRejected(fixture, stage, LelinkAuthWire.encode(fields));
                }
            }
        }
    }

    @Test public void rejectsIncorrectPayloadSizesAtEveryResponse() throws Exception {
        for (int stage : new int[]{2, 4, 6}) {
            int expected = stage == 2 ? 64 : stage == 4 ? 96 : 32;
            int tag = stage == 2 ? 3 : stage == 4 ? 4 : 5;
            for (int size : new int[]{0, 1, expected - 1, expected + 1, expected * 2}) {
                try (Fixture fixture = new Fixture()) {
                    assertRejected(fixture, stage, replace(fixture.responseFor(stage), tag, new byte[size]));
                }
            }
        }
    }

    @Test public void rejectsReceiverErrorsUnknownTagsAndMissingPayloads() throws Exception {
        for (int stage : new int[]{2, 4, 6}) {
            for (int unexpected : new int[]{0, 6, 7, 255}) {
                try (Fixture fixture = new Fixture()) {
                    List<LelinkAuthWire.Field> fields = new ArrayList<>(
                            LelinkAuthWire.decode(fixture.responseFor(stage)));
                    fields.add(field(unexpected, new byte[]{1}));
                    assertRejected(fixture, stage, LelinkAuthWire.encode(fields));
                }
            }
            try (Fixture fixture = new Fixture()) {
                fixture.responseFor(stage);
                assertRejected(fixture, stage, LelinkAuthWire.encode(List.of(
                        field(1, new byte[]{1}), field(2, new byte[]{(byte) stage}),
                        field(6, new byte[]{1}))));
            }
        }
    }

    @Test public void rejectsNullTruncatedOversizeAndDuplicateFramingAtEveryResponse() throws Exception {
        for (int stage : new int[]{2, 4, 6}) {
            for (int kind = 0; kind < 6; kind++) {
                try (Fixture fixture = new Fixture()) {
                    byte[] valid = fixture.responseFor(stage);
                    byte[] invalid;
                    if (kind == 0) invalid = null;
                    else if (kind == 1) invalid = new byte[0];
                    else if (kind == 2) invalid = Arrays.copyOf(valid, valid.length - 1);
                    else if (kind == 3) invalid = new byte[LelinkAuthWire.MAX_MESSAGE_SIZE + 1];
                    else if (kind == 4) invalid = concat(valid, Arrays.copyOf(valid, 9));
                    else invalid = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(1).putInt(-1).array();
                    assertRejected(fixture, stage, invalid);
                }
            }
        }
    }

    @Test public void rejectsLowOrderX25519KeysAndNeverEmitsAClientSignature() throws Exception {
        for (int value : new int[]{0, 1}) {
            try (Fixture fixture = new Fixture()) {
                byte[] verifyResponse = fixture.responseFor(4);
                byte[] invalid = payload(verifyResponse, 4);
                Arrays.fill(invalid, 0, 32, (byte) 0);
                invalid[0] = (byte) value;
                assertRejected(fixture, 4, replace(verifyResponse, 4, invalid));
            }
        }
    }

    @Test public void rejectsTamperingInEveryCiphertextBlock() throws Exception {
        for (int index : new int[]{32, 48, 64, 80, 95}) {
            try (Fixture fixture = new Fixture()) {
                byte[] response = fixture.responseFor(4);
                byte[] tampered = payload(response, 4);
                tampered[index] ^= 0x40;
                assertRejected(fixture, 4, replace(response, 4, tampered));
            }
        }
    }

    @Test public void rejectsAValidSignatureFromAnotherIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] verify = fixture.client.acceptSetupReturnVerify(fixture.peer.setup());
            byte[] response = fixture.peer.verify(verify,
                    new Ed25519PrivateKeyParameters(new SecureRandom()), false);
            assertRejected(fixture, 4, response);
        }
    }

    @Test public void rejectsSignatureOverReversedKeyOrder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] verify = fixture.client.acceptSetupReturnVerify(fixture.peer.setup());
            byte[] response = fixture.peer.verify(verify, fixture.peer.signing, true);
            assertRejected(fixture, 4, response);
        }
    }

    @Test public void rejectsSigningIdentityChangedAfterSetup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] response = fixture.peer.setup();
            byte[] identity = payload(response, 3);
            identity[5] ^= 0x40;
            byte[] verify = fixture.client.acceptSetupReturnVerify(replace(response, 3, identity));
            assertRejected(fixture, 4, fixture.peer.verify(verify));
        }
    }

    @Test public void finalNonceMustMatchEveryByteAndCannotBeTheClientNonce() throws Exception {
        for (int index : new int[]{0, 15, 31, 32}) {
            try (Fixture fixture = new Fixture()) {
                fixture.responseFor(6);
                byte[] nonce = fixture.peer.nonce.clone();
                if (index < 32) nonce[index] ^= 1;
                else nonce = fixture.peer.clientNonce.clone();
                assertRejected(fixture, 6, message(6, 5, nonce));
            }
        }
    }

    private static void assertRejected(Fixture fixture, int stage, byte[] response) {
        assertThrows(LelinkFreePairing.PairingException.class, () -> accept(fixture.client, stage, response));
        assertThrows(IllegalStateException.class, fixture.client::initialRequest);
        assertThrows(IllegalStateException.class, () -> fixture.client.acceptFinish(fixture.peer.finish()));
    }

    private static void accept(LelinkFreePairing client, int stage, byte[] response) throws Exception {
        if (stage == 2) client.acceptSetupReturnVerify(response);
        else if (stage == 4) client.acceptVerifyReturnFinish(response);
        else client.acceptFinish(response);
    }

    private static void assertMessage(byte[] message, int stage, int tag, int size) {
        List<LelinkAuthWire.Field> fields = LelinkAuthWire.decode(message);
        assertEquals(3, fields.size());
        assertEquals(1, fields.get(0).tag());
        assertArrayEquals(new byte[]{1}, fields.get(0).value());
        assertEquals(2, fields.get(1).tag());
        assertArrayEquals(new byte[]{(byte) stage}, fields.get(1).value());
        assertEquals(tag, fields.get(2).tag());
        assertEquals(size, fields.get(2).value().length);
    }

    private static final class Fixture implements AutoCloseable {
        final LelinkFreePairing client = new LelinkFreePairing();
        final Peer peer = new Peer(client.initialRequest());

        Fixture() throws Exception {}

        byte[] responseFor(int stage) throws Exception {
            if (stage == 2) return peer.setup();
            byte[] verify = client.acceptSetupReturnVerify(peer.setup());
            byte[] response = peer.verify(verify);
            if (stage == 4) return response;
            peer.verifyClientFinish(client.acceptVerifyReturnFinish(response));
            return peer.finish();
        }

        LelinkFreePairing.ControlSecrets complete() throws Exception {
            return client.acceptFinish(responseFor(6));
        }

        @Override public void close() { client.close(); }
    }

    /** A local receiver implementation; it does not call the production cryptographic helpers. */
    static final class Peer {
        final SecureRandom random = new SecureRandom();
        final Ed25519PrivateKeyParameters signing = new Ed25519PrivateKeyParameters(random);
        final X25519PrivateKeyParameters exchange = new X25519PrivateKeyParameters(random);
        final byte[] nonce = new byte[32];
        final byte[] clientSigning;
        final byte[] clientNonce;
        final byte[] serverExchange = exchange.generatePublicKey().getEncoded();
        byte[] clientExchange;
        byte[] shared;

        Peer(byte[] request) {
            byte[] identity = payload(request, 3);
            clientSigning = Arrays.copyOf(identity, 32);
            clientNonce = Arrays.copyOfRange(identity, 32, 64);
            random.nextBytes(nonce);
        }

        byte[] setup() {
            return message(2, 3, concat(signing.generatePublicKey().getEncoded(), nonce));
        }

        byte[] verify(byte[] request) throws Exception { return verify(request, signing, false); }

        byte[] verify(byte[] request, Ed25519PrivateKeyParameters identity, boolean reversed) throws Exception {
            assertMessage(request, 3, 4, 64);
            byte[] value = payload(request, 4);
            assertArrayEquals(clientSigning, Arrays.copyOfRange(value, 32, 64));
            clientExchange = Arrays.copyOf(value, 32);
            shared = new byte[32];
            exchange.generateSecret(new X25519PublicKeyParameters(clientExchange, 0), shared, 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, identity);
            byte[] data = reversed ? concat(clientExchange, serverExchange) : concat(serverExchange, clientExchange);
            signer.update(data, 0, data.length);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, signer.generateSignature(),
                    "LELINK-VERIFY_SIGNATURE-KEY", "LELINK-VERIFY-SIGNATURE-NONCE");
            return message(4, 4, concat(serverExchange, encrypted));
        }

        void verifyClientFinish(byte[] request) throws Exception {
            assertMessage(request, 5, 5, 64);
            byte[] signature = cipher(Cipher.DECRYPT_MODE, payload(request, 5),
                    "LELINK-VERIFY_IDENTITY-KEY", "LEINK-VERIFY-IDENTITY-NONCE");
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, new Ed25519PublicKeyParameters(clientSigning, 0));
            byte[] expected = concat(clientExchange, serverExchange);
            verifier.update(expected, 0, expected.length);
            assertTrue("Receiver must verify client's identity signature", verifier.verifySignature(signature));
        }

        byte[] finish() { return message(6, 5, nonce); }

        byte[] control(String label) throws Exception {
            return digest(concat(mediaSeed(), label.getBytes(StandardCharsets.US_ASCII)), 32);
        }

        byte[] mediaSeed() throws Exception {
            byte[] h0 = digest(concat(clientNonce, nonce), 32);
            byte[] h1 = digest(concat(shared, h0), 32);
            return digest(concat(shared, h1), 32);
        }

        private byte[] cipher(int mode, byte[] input, String keyLabel, String nonceLabel) throws Exception {
            byte[] key = digest(concat(keyLabel.getBytes(StandardCharsets.US_ASCII), shared), 16);
            byte[] iv = digest(concat(nonceLabel.getBytes(StandardCharsets.US_ASCII), shared), 16);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        }

        private byte[] digest(byte[] input, int size) throws Exception {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-512").digest(input), size);
        }
    }

    private static byte[] reversed(byte[] message) {
        List<LelinkAuthWire.Field> fields = new ArrayList<>(LelinkAuthWire.decode(message));
        java.util.Collections.reverse(fields);
        return LelinkAuthWire.encode(fields);
    }

    private static byte[] replace(byte[] message, int tag, byte[] replacement) {
        List<LelinkAuthWire.Field> fields = new ArrayList<>();
        for (LelinkAuthWire.Field field : LelinkAuthWire.decode(message)) {
            fields.add(field.tag() == tag ? field(tag, replacement) : field);
        }
        return LelinkAuthWire.encode(fields);
    }

    private static byte[] payload(byte[] message, int tag) {
        for (LelinkAuthWire.Field field : LelinkAuthWire.decode(message)) {
            if (field.tag() == tag) return field.value();
        }
        throw new AssertionError("Missing test message payload " + tag);
    }

    private static byte[] message(int stage, int tag, byte[] payload) {
        return LelinkAuthWire.encode(List.of(field(1, new byte[]{1}),
                field(2, new byte[]{(byte) stage}), field(tag, payload)));
    }

    private static LelinkAuthWire.Field field(int tag, byte[] value) {
        return new LelinkAuthWire.Field(tag, value);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteBuffer buffer = ByteBuffer.allocate(first.length + second.length);
        buffer.put(first).put(second);
        return buffer.array();
    }
}
