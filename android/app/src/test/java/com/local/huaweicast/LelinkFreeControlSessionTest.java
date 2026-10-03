package com.local.huaweicast;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LelinkFreeControlSessionTest {
    @Test public void onlyExplicitFreeDiscoveryCanCreateOrBindASocket() throws Exception {
        List<Map<String, byte[]>> modes = List.of(
                Map.of(),
                Map.of("lelinkport", bytes("52244")),
                Map.of("htv", bytes("1"), "atv", bytes("0")),
                attributes("2", "0"), attributes("2", "1"), attributes("2", "2"),
                attributes("1", "1"), attributes("3", "0"));
        for (Map<String, byte[]> attributes : modes) {
            AtomicInteger connects = new AtomicInteger();
            AtomicInteger binds = new AtomicInteger();
            IOException error = assertThrows(IOException.class, () -> LelinkFreeControlSession.connect(
                    endpoint(attributes), ignored -> binds.incrementAndGet(), (receiver, binder) -> {
                        connects.incrementAndGet();
                        throw new AssertionError("Ineligible discovery must not create a socket");
                    }));
            assertTrue(error.getMessage().contains("htv=1, atv=0"));
            assertEquals(0, connects.get());
            assertEquals(0, binds.get());
        }
    }

    @Test public void stalePasswordOrUnknownPlayerModeNeverSendsPairing() throws Exception {
        for (int[] mode : new int[][]{{2, 0}, {2, 1}, {2, 2}, {1, 1}, {0, 0}, {9, 9}}) {
            try (PeerSocket socket = new PeerSocket(playerInfo(mode[0], mode[1]))) {
                IOException error = assertThrows(IOException.class, () -> connect(socket));
                assertTrue(error.getMessage().contains("no longer offers FREE"));
                assertOnlyPlayerInfoThenClosed(socket);
            }
        }
    }

    @Test public void missingMalformedAndNonPlistPlayerInfoNeverSendsPairing() throws Exception {
        for (String reply : new String[]{"", "{}", "<plist><dict/></plist>",
                "<plist><dict><key>htv</key><integer>1</integer></dict></plist>",
                "<plist><dict><key>htv</key><string>free</string><key>atv</key><integer>0</integer></dict></plist>"}) {
            try (PeerSocket socket = new PeerSocket(bytes(reply))) {
                assertThrows(IOException.class, () -> connect(socket));
                assertOnlyPlayerInfoThenClosed(socket);
            }
        }
    }

    @Test public void completesOnOneSocketAndOnlyEncryptsAfterPlaintextM6() throws Exception {
        try (PeerSocket socket = new PeerSocket(playerInfo(1, 0));
             LelinkFreeControlSession session = connect(socket)) {
            assertEquals(1, socket.connects);
            assertEquals(1, socket.binds);
            assertEquals(4, socket.sent.size());
            assertTrue(session.playerInfo().advertisesFreePairing());
            assertArrayEquals(socket.peer.mediaSeed(), session.mediaSeed());
            assertFalse(session.isClosed());
            assertFalse(socket.closed);
            assertThrows(IllegalStateException.class,
                    () -> session.control().enableEncryption(new byte[32], new byte[32]));
            assertEquals(200, session.control().readPlayerInfo().status());
            assertEquals(5, socket.sent.size());
            assertTrue(socket.encryptedRequestRead);
            for (int i = 0; i < 4; i++) {
                String request = new String(socket.sent.get(i), StandardCharsets.ISO_8859_1);
                assertFalse(request.contains("/stream"));
                assertFalse(request.contains("SETUP /"));
                assertFalse(request.contains("Authorization:"));
            }
        }
    }

    @Test public void everyNon200StatusIsTerminalAndPreservesNativeStatus() throws Exception {
        for (int stage = 0; stage < 4; stage++) {
            for (int status : new int[]{204, 302, 401, 403, 600, 601}) {
                try (PeerSocket socket = new PeerSocket(playerInfo(1, 0))) {
                    socket.rejectAt = stage;
                    socket.rejectStatus = status;
                    LelinkFreeControlSession.ReceiverRejectedException error = assertThrows(
                            LelinkFreeControlSession.ReceiverRejectedException.class, () -> connect(socket));
                    assertEquals(status, error.statusCode());
                    assertTrue(error.getMessage().contains("status " + status));
                    assertEquals(stage == 0 ? "player-info" : stage == 1 ? "M2 setup"
                            : stage == 2 ? "M4 verify" : "M6 finish", error.stage());
                    assertEquals(stage + 1, socket.sent.size());
                    assertEquals(1, socket.connects);
                    assertTrue(socket.closed);
                }
            }
        }
    }

    @Test public void invalidPairingRepliesCloseWithoutRetryOrEncryption() throws Exception {
        for (int stage = 1; stage <= 3; stage++) {
            try (PeerSocket socket = new PeerSocket(playerInfo(1, 0))) {
                socket.corruptAt = stage;
                IOException error = assertThrows(IOException.class, () -> connect(socket));
                assertTrue(error.getCause() instanceof LelinkFreePairing.PairingException);
                assertEquals(stage + 1, socket.sent.size());
                assertTrue(socket.closed);
                assertFalse(socket.encryptedRequestRead);
            }
        }
    }

    @Test public void wrongFinalNonceDoesNotExposeAControlSession() throws Exception {
        try (PeerSocket socket = new PeerSocket(playerInfo(1, 0))) {
            socket.wrongFinalNonce = true;
            IOException error = assertThrows(IOException.class, () -> connect(socket));
            assertTrue(error.getCause() instanceof LelinkFreePairing.PairingException);
            assertEquals(4, socket.sent.size());
            assertTrue(socket.closed);
            assertFalse(socket.encryptedRequestRead);
        }
    }

    @Test public void sessionOwnsItsTransportAndDefensiveMediaSeed() throws Exception {
        try (PeerSocket socket = new PeerSocket(playerInfo(1, 0))) {
            LelinkFreeControlSession session = connect(socket);
            byte[] expected = socket.peer.mediaSeed();
            byte[] first = session.mediaSeed();
            Arrays.fill(first, (byte) 0);
            assertArrayEquals(expected, session.mediaSeed());
            LelinkControlClient control = session.control();
            session.close();
            session.close();
            assertTrue(session.isClosed());
            assertTrue(control.isClosed());
            assertTrue(socket.closed);
            assertThrows(IllegalStateException.class, session::mediaSeed);
            assertThrows(IllegalStateException.class, session::control);
            assertThrows(IOException.class, control::readPlayerInfo);
            assertEquals(4, socket.sent.size());
        }
    }

    @Test public void socketBindingFailureIsPropagatedAndClosesWithoutARequest() throws Exception {
        try (PeerSocket socket = new PeerSocket(playerInfo(1, 0))) {
            IOException expected = new IOException("Binding cancelled");
            IOException actual = assertThrows(IOException.class, () -> LelinkFreeControlSession.connect(
                    endpoint(attributes("1", "0")), ignored -> { throw expected; },
                    (receiver, binder) -> LelinkControlClient.connect(receiver, binder, 2000, () -> socket)));
            assertSame(expected, actual);
            assertEquals(0, socket.connects);
            assertEquals(0, socket.sent.size());
            assertTrue(socket.closed);
        }
    }

    @Test public void transportFailureAfterPlayerInfoClosesAndNeverRetries() throws Exception {
        try (PeerSocket socket = new PeerSocket(playerInfo(1, 0))) {
            socket.failAt = 1;
            IOException error = assertThrows(IOException.class, () -> connect(socket));
            assertEquals("Connection lost", error.getMessage());
            assertEquals(2, socket.sent.size());
            assertEquals(1, socket.connects);
            assertTrue(socket.closed);
        }
    }

    private static LelinkFreeControlSession connect(PeerSocket socket) throws Exception {
        return LelinkFreeControlSession.connect(endpoint(attributes("1", "0")), ignored -> socket.binds++,
                (receiver, binder) -> LelinkControlClient.connect(receiver, binder, 2000, () -> socket));
    }

    private static void assertOnlyPlayerInfoThenClosed(PeerSocket socket) {
        assertTrue(socket.closed);
        assertEquals(1, socket.connects);
        assertEquals(1, socket.binds);
        assertEquals(1, socket.sent.size());
        assertTrue(new String(socket.sent.get(0), StandardCharsets.US_ASCII)
                .startsWith("GET /lelink-player-info HTTP/1.1\r\n"));
    }

    private static Map<String, byte[]> attributes(String htv, String atv) {
        return Map.of("lelinkport", bytes("52244"), "htv", bytes(htv), "atv", bytes(atv));
    }

    private static LelinkEndpoint endpoint(Map<String, byte[]> attributes) throws Exception {
        return LelinkEndpoint.from("receiver", InetAddress.getByName("10.0.0.8"), 7100, attributes);
    }

    private static byte[] playerInfo(int htv, int atv) {
        return bytes("<plist version=\"1.0\"><dict><key>htv</key><integer>" + htv
                + "</integer><key>atv</key><integer>" + atv + "</integer></dict></plist>");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] response(int status, byte[] body) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(bytes("HTTP/1.1 " + status + " Result\r\nContent-Length: " + body.length + "\r\n\r\n"));
        result.write(body);
        return result.toByteArray();
    }

    /** The peer responds only after each complete request; no real receiver or network is used. */
    private static final class PeerSocket extends Socket {
        final byte[] info;
        final List<byte[]> sent = new ArrayList<>();
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        ByteArrayInputStream reply = new ByteArrayInputStream(new byte[0]);
        LelinkFreePairingTest.Peer peer;
        boolean closed;
        boolean wrongFinalNonce;
        boolean encryptedRequestRead;
        int connects;
        int binds;
        int rejectAt = -1;
        int rejectStatus;
        int corruptAt = -1;
        int failAt = -1;

        PeerSocket(byte[] info) { this.info = info; }

        @Override public void connect(SocketAddress address, int timeout) { connects++; }
        @Override public void setTcpNoDelay(boolean enabled) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    if (closed) throw new IOException("Socket is closed");
                    return reply.read();
                }
                @Override public int read(byte[] target, int offset, int size) throws IOException {
                    if (closed) throw new IOException("Socket is closed");
                    return reply.read(target, offset, Math.min(size, 7));
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { pending.write(value); }
                @Override public void write(byte[] bytes, int offset, int size) { pending.write(bytes, offset, size); }
                @Override public void flush() throws IOException {
                    if (closed) throw new IOException("Socket is closed");
                    assertEquals("Client must consume the whole prior response", 0, reply.available());
                    byte[] request = pending.toByteArray();
                    pending.reset();
                    int stage = sent.size();
                    sent.add(request);
                    try { reply = new ByteArrayInputStream(accept(stage, request)); }
                    catch (IOException error) { throw error; }
                    catch (Exception error) { throw new IOException("Test peer failed", error); }
                }
            };
        }

        private byte[] accept(int stage, byte[] request) throws Exception {
            if (stage == failAt) throw new IOException("Connection lost");
            if (stage == rejectAt) return response(rejectStatus, new byte[0]);
            if (stage == corruptAt) return response(200, new byte[]{0});
            if (stage == 4) {
                byte[] key = peer.control("LELINK-IDENTITY-KEY");
                byte[] nonce = Arrays.copyOf(peer.control("LELINK-IDENTITY-NONCE"), 8);
                try (LelinkSecureRecord.Decoder decoder = new LelinkSecureRecord.Decoder(key, nonce);
                     LelinkSecureRecord.Encoder encoder = new LelinkSecureRecord.Encoder(key, nonce)) {
                    byte[] plain = decoder.read(new ByteArrayInputStream(request));
                    assertTrue(new String(plain, StandardCharsets.US_ASCII)
                            .startsWith("GET /lelink-player-info HTTP/1.1\r\n"));
                    encryptedRequestRead = true;
                    return encoder.encode(response(200, info));
                }
            }
            String text = new String(request, StandardCharsets.ISO_8859_1);
            String expected = stage == 0 ? "GET /lelink-player-info" : stage == 1
                    ? "POST /lelink-setup" : "POST /lelink-verify";
            assertTrue(text.startsWith(expected + " HTTP/1.1\r\n"));
            int bodyStart = text.indexOf("\r\n\r\n") + 4;
            assertTrue(bodyStart >= 4);
            byte[] body = Arrays.copyOfRange(request, bodyStart, request.length);
            if (stage == 0) {
                assertEquals(0, body.length);
                return response(200, info);
            }
            if (stage == 1) {
                peer = new LelinkFreePairingTest.Peer(body);
                return response(200, peer.setup());
            }
            if (stage == 2) return response(200, peer.verify(body));
            assertEquals(3, stage);
            peer.verifyClientFinish(body);
            byte[] finish = peer.finish();
            if (wrongFinalNonce) finish[finish.length - 1] ^= 1;
            return response(200, finish);
        }

        @Override public void close() { closed = true; }
    }
}
