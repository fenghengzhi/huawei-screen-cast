package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LelinkControlClientTest {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static LelinkEndpoint endpoint() throws Exception {
        return LelinkEndpoint.from("receiver", InetAddress.getByName("10.0.0.8"), 7100,
                Map.of("lelinkport", bytes("52244")));
    }
    private static LelinkControlClient client(FakeSocket socket) throws Exception {
        return LelinkControlClient.connect(endpoint(), ignored -> {}, 2000, () -> socket);
    }
    private static String response(int status, String body) {
        return "HTTP/1.1 " + status + " Result\r\nContent-Length: " + bytes(body).length + "\r\n\r\n" + body;
    }

    @Test public void setupAndVerifyUseOneBoundSocketAndHonestFixedHeaders() throws Exception {
        FakeSocket socket = new FakeSocket(response(200, "one") + response(200, "two"));
        AtomicReference<Socket> bound = new AtomicReference<>();
        try (LelinkControlClient client = LelinkControlClient.connect(endpoint(), value -> {
            assertNull(socket.target);
            bound.set(value);
        }, 2000, () -> socket)) {
            assertSame(socket, bound.get());
            assertEquals(new InetSocketAddress("10.0.0.8", 52244), socket.target);
            assertEquals(1, socket.connects);
            assertEquals(0, socket.output.size());
            assertArrayEquals(bytes("one"), client.setup(new byte[]{1, 2}).body());
            assertArrayEquals(bytes("two"), client.verify(new byte[]{3}).body());
            String wire = socket.output.toString(StandardCharsets.ISO_8859_1);
            assertTrue(wire.startsWith("POST /lelink-setup HTTP/1.1\r\nHost: 10.0.0.8:52244\r\n"));
            assertTrue(wire.contains("POST /lelink-verify HTTP/1.1\r\n"));
            assertTrue(wire.contains("User-Agent: HuaweiCast/1.0\r\n"));
            assertTrue(wire.contains("Content-Type: application/octet-stream\r\n"));
            assertTrue(wire.contains("LeLink-Platform: Android\r\n"));
            String firstSession = headerValue(wire, "LeLink-Session-ID");
            String firstUid = headerValue(wire, "LeLink-Client-UID");
            assertNotEquals(firstSession, firstUid);
            assertEquals(firstSession, java.util.UUID.fromString(firstSession).toString());
            assertEquals(firstUid, java.util.UUID.fromString(firstUid).toString());
            String verify = wire.substring(wire.indexOf("POST /lelink-verify"));
            assertEquals(firstSession, headerValue(verify, "LeLink-Session-ID"));
            assertEquals(firstUid, headerValue(verify, "LeLink-Client-UID"));
            assertFalse(wire.contains("\r\nProtocol:"));
            assertFalse(wire.contains("X-Apple"));
            assertFalse(wire.contains("X-LeLink"));
            assertTrue(wire.contains("Content-Length: 2\r\n\r\n\u0001\u0002"));
            assertFalse(wire.contains("HappyCast"));
            assertFalse(wire.contains("/server-info"));
            assertFalse(wire.contains("/stream"));
            assertFalse(wire.contains("Authorization:"));
            assertFalse(client.isClosed());
        }
        assertTrue(socket.closed);
    }

    @Test public void connectionIdentitiesAreFreshAndNotPersistent() throws Exception {
        FakeSocket first = new FakeSocket(response(200, "ok"));
        FakeSocket second = new FakeSocket(response(200, "ok"));
        try (LelinkControlClient one = client(first); LelinkControlClient two = client(second)) {
            one.readPlayerInfo();
            two.readPlayerInfo();
            for (String field : new String[]{"LeLink-Session-ID", "LeLink-Client-UID"}) {
                assertNotEquals(headerValue(first.output.toString(StandardCharsets.US_ASCII), field),
                        headerValue(second.output.toString(StandardCharsets.US_ASCII), field));
            }
        }
    }

    @Test public void serverInfoIsOnlySentWhenExplicitlyRequested() throws Exception {
        FakeSocket socket = new FakeSocket(response(200, "<plist/>"));
        try (LelinkControlClient client = client(socket)) {
            assertEquals(0, socket.output.size());
            assertEquals(200, client.readServerInfo().status());
            String wire = socket.output.toString(StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("GET /server-info HTTP/1.1\r\n"));
            assertFalse(wire.contains("application/octet-stream"));
            assertFalse(wire.contains("/stream.xml"));
        }
    }

    @Test public void nativePlayerInfoIsAnExplicitFixedRoute() throws Exception {
        FakeSocket socket = new FakeSocket(response(200, "<plist/>"));
        try (LelinkControlClient client = client(socket)) {
            assertEquals(200, client.readPlayerInfo().status());
            String wire = socket.output.toString(StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("GET /lelink-player-info HTTP/1.1\r\n"));
            assertFalse(wire.contains("/server-info"));
            assertFalse(wire.contains("/stream"));
        }
    }

    @Test public void explicitUpgradePreservesPartialAndCoalescedEncryptedHttpResponses() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[32];
        Arrays.fill(key, (byte) 7);
        Arrays.fill(nonce, (byte) 9);
        ByteArrayOutputStream replies = new ByteArrayOutputStream();
        replies.write(bytes(response(200, "M6")));
        byte[] twoResponses = bytes(response(200, "first") + response(200, "second"));
        try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(key, Arrays.copyOf(nonce, 8))) {
            replies.write(receiver.encode(Arrays.copyOfRange(twoResponses, 0, 11)));
            replies.write(receiver.encode(Arrays.copyOfRange(twoResponses, 11, twoResponses.length)));
        }
        FakeSocket socket = new FakeSocket(replies.toByteArray());
        try (LelinkControlClient client = client(socket)) {
            assertArrayEquals(bytes("M6"), client.verify(new byte[]{5}).body());
            int plainLength = socket.output.size();
            client.enableEncryption(key, nonce);
            key[0] ^= 1;
            nonce[0] ^= 1;
            assertArrayEquals(bytes("first"), client.readPlayerInfo().body());
            assertArrayEquals(bytes("second"), client.readServerInfo().body());
            key[0] ^= 1;
            nonce[0] ^= 1;
            byte[] wire = socket.output.toByteArray();
            InputStream encrypted = new ByteArrayInputStream(wire, plainLength, wire.length - plainLength);
            try (LelinkSecureRecord.Decoder receiver = new LelinkSecureRecord.Decoder(key, Arrays.copyOf(nonce, 8))) {
                String first = new String(receiver.read(encrypted), StandardCharsets.US_ASCII);
                String second = new String(receiver.read(encrypted), StandardCharsets.US_ASCII);
                assertTrue(first.startsWith("GET /lelink-player-info HTTP/1.1\r\n"));
                assertTrue(second.startsWith("GET /server-info HTTP/1.1\r\n"));
                assertEquals(-1, encrypted.read());
            }
            assertThrows(IllegalStateException.class, () -> client.enableEncryption(key, nonce));
        }
    }

    @Test public void encryptedAuthenticationFailureNeverFallsBackToPlaintext() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[32];
        byte[] reply;
        try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(key, new byte[8])) {
            reply = receiver.encode(bytes(response(200, "secret")));
        }
        reply[reply.length - 1] ^= 1;
        ByteArrayOutputStream replies = new ByteArrayOutputStream();
        replies.write(reply);
        replies.write(bytes(response(200, "must not accept")));
        FakeSocket socket = new FakeSocket(replies.toByteArray());
        try (LelinkControlClient client = client(socket)) {
            client.enableEncryption(key, nonce);
            assertThrows(IOException.class, client::readPlayerInfo);
            assertTrue(client.isClosed());
            int sent = socket.output.size();
            assertThrows(IOException.class, client::readPlayerInfo);
            assertEquals(sent, socket.output.size());
            assertFalse(socket.output.toString(StandardCharsets.ISO_8859_1).contains("GET /"));
        }
    }

    @Test public void streamSetupRequiresEncryptionAndUsesExplicitBinaryPlistRequest() throws Exception {
        byte[] reply;
        try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(new byte[32], new byte[8])) {
            reply = receiver.encode(bytes(response(200, "setup")));
        }
        FakeSocket socket = new FakeSocket(reply);
        byte[] plist = bytes("bplist00fixture");
        try (LelinkControlClient client = client(socket)) {
            assertThrows(IllegalStateException.class, () -> client.setupStream(plist));
            assertEquals(0, socket.output.size());
            client.enableEncryption(new byte[32], new byte[32]);
            assertThrows(IllegalArgumentException.class, () -> client.setupStream(bytes("<plist/>")));
            assertEquals(0, socket.output.size());
            assertEquals(200, client.setupStream(plist).status());
            try (LelinkSecureRecord.Decoder receiver = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
                String request = new String(receiver.read(new ByteArrayInputStream(socket.output.toByteArray())), StandardCharsets.US_ASCII);
                assertTrue(request.startsWith("SETUP / HTTP/1.1\r\n"));
                assertTrue(request.contains("Content-Type: application/plist-binary\r\n"));
                assertTrue(request.endsWith("\r\n\r\nbplist00fixture"));
            }
        }
    }

    @Test public void streamTeardownReusesEncryptedSetupConnectionAndAcceptsEmptyResponse() throws Exception {
        ByteArrayOutputStream replies = new ByteArrayOutputStream();
        try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(new byte[32], new byte[8])) {
            replies.write(receiver.encode(bytes(response(200, "setup"))));
            replies.write(receiver.encode(bytes(response(200, ""))));
        }
        FakeSocket socket = new FakeSocket(replies.toByteArray());
        try (LelinkControlClient client = client(socket)) {
            client.enableEncryption(new byte[32], new byte[32]);
            assertEquals(200, client.setupStream(bytes("bplist00setup")).status());
            LelinkControlClient.Response result = client.teardownStream(bytes("bplist00teardown"));
            assertEquals(200, result.status());
            assertArrayEquals(new byte[0], result.body());
            assertFalse(client.isClosed());
            assertEquals(1, socket.connects);
            InputStream encrypted = new ByteArrayInputStream(socket.output.toByteArray());
            try (LelinkSecureRecord.Decoder receiver = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
                String setup = new String(receiver.read(encrypted), StandardCharsets.US_ASCII);
                String teardown = new String(receiver.read(encrypted), StandardCharsets.US_ASCII);
                assertTrue(setup.startsWith("SETUP / HTTP/1.1\r\n"));
                assertTrue(teardown.startsWith("TEARDOWN / HTTP/1.1\r\n"));
                assertTrue(teardown.contains("Content-Type: application/plist-binary\r\n"));
                assertTrue(teardown.contains("Content-Length: 16\r\n"));
                assertTrue(teardown.endsWith("\r\n\r\nbplist00teardown"));
                assertEquals(headerValue(setup, "LeLink-Session-ID"), headerValue(teardown, "LeLink-Session-ID"));
                assertEquals(headerValue(setup, "LeLink-Client-UID"), headerValue(teardown, "LeLink-Client-UID"));
                assertEquals(-1, encrypted.read());
            }
        }
    }

    @Test public void streamTeardownRequiresEncryptionAndValidBinaryPayloadBeforeWriting() throws Exception {
        byte[] reply;
        try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(new byte[32], new byte[8])) {
            reply = receiver.encode(bytes(response(200, "")));
        }
        FakeSocket socket = new FakeSocket(reply);
        byte[] plist = bytes("bplist00fixture");
        try (LelinkControlClient client = client(socket)) {
            assertThrows(IllegalStateException.class, () -> client.teardownStream(plist));
            assertEquals(0, socket.output.size());
            client.enableEncryption(new byte[32], new byte[32]);
            for (byte[] invalid : new byte[][]{null, new byte[0], bytes("bplist0"), bytes("<plist/>"),
                    bytes("bplist01fixture"), new byte[LelinkControlClient.MAX_BODY + 1]}) {
                assertThrows(IllegalArgumentException.class, () -> client.teardownStream(invalid));
                assertFalse(client.isClosed());
                assertEquals(0, socket.output.size());
            }
            assertEquals(200, client.teardownStream(plist).status());
            try (LelinkSecureRecord.Decoder receiver = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
                String request = new String(receiver.read(new ByteArrayInputStream(socket.output.toByteArray())), StandardCharsets.US_ASCII);
                assertTrue(request.startsWith("TEARDOWN / HTTP/1.1\r\n"));
            }
        }
    }

    @Test public void streamTeardownPreservesAuthorizationRejectionWithoutFallbackOrReconnect() throws Exception {
        for (int status : new int[]{401, 403}) {
            byte[] reply;
            try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(new byte[32], new byte[8])) {
                reply = receiver.encode(bytes(response(status, "denied") + response(200, "never")));
            }
            FakeSocket socket = new FakeSocket(reply);
            try (LelinkControlClient client = client(socket)) {
                client.enableEncryption(new byte[32], new byte[32]);
                LelinkControlClient.Response result = client.teardownStream(bytes("bplist00fixture"));
                assertEquals(status, result.status());
                assertArrayEquals(bytes("denied"), result.body());
                assertTrue(client.isClosed());
                assertTrue(socket.closed);
                int sent = socket.output.size();
                assertThrows(IOException.class, client::readPlayerInfo);
                assertEquals(sent, socket.output.size());
                assertEquals(1, socket.connects);
                InputStream encrypted = new ByteArrayInputStream(socket.output.toByteArray());
                try (LelinkSecureRecord.Decoder receiver = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
                    String request = new String(receiver.read(encrypted), StandardCharsets.US_ASCII);
                    assertTrue(request.startsWith("TEARDOWN / HTTP/1.1\r\n"));
                    assertFalse(request.contains("Authorization:"));
                    assertEquals(-1, encrypted.read());
                }
            }
        }
    }

    @Test public void encryptionRequiresExactSizesAndAnOpenConnection() throws Exception {
        try (LelinkControlClient client = client(new FakeSocket(""))) {
            assertThrows(IllegalArgumentException.class, () -> client.enableEncryption(new byte[31], new byte[32]));
            assertThrows(IllegalArgumentException.class, () -> client.enableEncryption(new byte[32], new byte[8]));
            assertThrows(IllegalArgumentException.class, () -> client.enableEncryption(null, new byte[32]));
            assertFalse(client.isClosed());
            client.close();
            assertThrows(IOException.class, () -> client.enableEncryption(new byte[32], new byte[32]));
        }
    }

    @Test public void oversizedEncryptedHttpNeverConsumesCipherOrClosesConnection() throws Exception {
        byte[] reply;
        try (LelinkSecureRecord.Encoder receiver = new LelinkSecureRecord.Encoder(new byte[32], new byte[8])) {
            reply = receiver.encode(bytes(response(200, "ok")));
        }
        FakeSocket socket = new FakeSocket(reply);
        try (LelinkControlClient client = client(socket)) {
            client.enableEncryption(new byte[32], new byte[32]);
            assertThrows(IllegalArgumentException.class, () -> client.verify(new byte[LelinkControlClient.MAX_BODY]));
            assertFalse(client.isClosed());
            assertEquals(0, socket.output.size());
            assertEquals(200, client.readPlayerInfo().status());
            try (LelinkSecureRecord.Decoder receiver = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
                String request = new String(receiver.read(new ByteArrayInputStream(socket.output.toByteArray())), StandardCharsets.US_ASCII);
                assertTrue(request.startsWith("GET /lelink-player-info HTTP/1.1"));
            }
        }
    }

    @Test public void nativeStatusesAndBodiesArePreservedWithoutRedirectsOrFallback() throws Exception {
        for (int status : new int[]{300, 302, 400, 500, 600, 601, 606, 607, 999}) {
            FakeSocket socket = new FakeSocket("HTTP/1.1 " + status + " Result\r\n"
                    + "Location: http://example.invalid/never\r\nContent-Length: 3\r\n\r\nwhy");
            try (LelinkControlClient client = client(socket)) {
                LelinkControlClient.Response result = client.setup(new byte[]{1});
                assertEquals(status, result.status());
                assertArrayEquals(bytes("why"), result.body());
                assertEquals("http://example.invalid/never", result.headers().get("location"));
                assertEquals(1, socket.connects);
                assertFalse(socket.output.toString(StandardCharsets.US_ASCII).contains("GET "));
            }
        }
    }

    @Test public void authorizationRejectionIsTerminal() throws Exception {
        for (int status : new int[]{401, 403}) {
            FakeSocket socket = new FakeSocket(response(status, "denied") + response(200, "never"));
            try (LelinkControlClient client = client(socket)) {
                assertEquals(status, client.setup(new byte[]{1}).status());
                assertTrue(client.isClosed());
                assertTrue(socket.closed);
                int sent = socket.output.size();
                assertThrows(IOException.class, () -> client.verify(new byte[]{2}));
                assertEquals(sent, socket.output.size());
            }
        }
    }

    @Test public void responsesAreDefensiveAndHeadersAreCaseInsensitive() throws Exception {
        FakeSocket socket = new FakeSocket("HTTP/1.1 200 OK\r\ncOnTeNt-LeNgTh: 3\r\nX-Test: first\r\nx-test: second\r\n\r\nabc");
        try (LelinkControlClient client = client(socket)) {
            var result = client.setup(new byte[]{1});
            byte[] first = result.body();
            first[0] = 'z';
            assertArrayEquals(bytes("abc"), result.body());
            assertEquals("first, second", result.headers().get("x-test"));
            assertThrows(UnsupportedOperationException.class, () -> result.headers().put("x", "y"));
        }
    }

    @Test public void fragmentedChunkedBodyAndNextResponseStayAligned() throws Exception {
        FakeSocket socket = new FakeSocket("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "2;test=yes\r\nab\r\n3\r\ncde\r\n0\r\nX-Trailer: ok\r\n\r\n" + response(200, "next"));
        try (LelinkControlClient client = client(socket)) {
            assertArrayEquals(bytes("abcde"), client.setup(new byte[]{1}).body());
            assertArrayEquals(bytes("next"), client.verify(new byte[]{2}).body());
        }
    }

    @Test public void acceptsBoundedInterimResponsesAndBodyless204() throws Exception {
        FakeSocket socket = new FakeSocket("HTTP/1.1 100 Continue\r\n\r\n"
                + "HTTP/1.1 103 Early Hints\r\nX-Test: ok\r\n\r\n"
                + "HTTP/1.1 204 No Content\r\n\r\n" + response(200, "next"));
        try (LelinkControlClient client = client(socket)) {
            assertEquals(204, client.setup(new byte[]{1}).status());
            assertArrayEquals(bytes("next"), client.verify(new byte[]{2}).body());
        }
    }

    @Test public void refusesMalformedOrAmbiguousFramingAndClosesConnection() throws Exception {
        String[] replies = {
                "\r\n\r\n",
                "HTTP/1.1 020 Nope\r\nContent-Length: 0\r\n\r\n",
                "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\na",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
                "HTTP/1.1 200 OK\r\n Folded: bad\r\n\r\n",
                "HTTP/1.1 200 OK\r\nBad Name: value\r\n\r\n",
                "HTTP/1.1 200 OK\nBad: yes\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort",
                "HTTP/1.1 101 Switching Protocols\r\n\r\n",
                "HTTP/1.1 204 No Content\r\nContent-Length: 1\r\n\r\na",
                "HTTP/1.1 100 Continue\r\nContent-Length: 1\r\n\r\na",
                "HTTP/1.1 100 Continue\r\n\r\n".repeat(6)
        };
        for (String reply : replies) {
            FakeSocket socket = new FakeSocket(reply);
            try (LelinkControlClient client = client(socket)) {
                assertThrows(reply, IOException.class, () -> client.setup(new byte[]{1}));
                assertTrue(client.isClosed());
                assertTrue(socket.closed);
            }
        }
    }

    @Test public void limitsHeadersBodiesAndChunkFraming() throws Exception {
        String chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
        String[] replies = {
                "HTTP/1.1 200 OK\r\nX: " + "x".repeat(LelinkControlClient.MAX_HEADERS) + "\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 32769\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 999999999\r\n\r\n",
                "HTTP/1.0 200 OK\r\n\r\n" + "x".repeat(LelinkControlClient.MAX_BODY + 1),
                chunked + "8001\r\n",
                chunked + "ffffffff\r\n",
                chunked + "g\r\n",
                chunked + "3\r\nabcX\n",
                chunked + "1\r\na\r\n".repeat(2000),
                chunked + "0\r\nContent-Length: 0\r\n\r\n",
                chunked + "0\r\nX: " + "x".repeat(LelinkControlClient.MAX_HEADERS) + "\r\n\r\n"
        };
        for (String reply : replies) {
            try (LelinkControlClient client = client(new FakeSocket(reply))) {
                assertThrows(IOException.class, () -> client.setup(new byte[]{1}));
                assertTrue(client.isClosed());
            }
        }
    }

    @Test public void closeDelimitedOrExplicitCloseResponseCannotBeReused() throws Exception {
        for (String reply : new String[]{"HTTP/1.0 200 OK\r\n\r\ndata",
                "HTTP/1.1 200 OK\r\nConnection: keep-alive, close\r\nContent-Length: 4\r\n\r\ndata"}) {
            try (LelinkControlClient client = client(new FakeSocket(reply))) {
                assertArrayEquals(bytes("data"), client.setup(new byte[]{1}).body());
                assertTrue(client.isClosed());
            }
        }
    }

    @Test public void invalidPayloadNeverWritesAndBindingFailureClosesSocket() throws Exception {
        FakeSocket socket = new FakeSocket(response(200, "ok"));
        try (LelinkControlClient client = client(socket)) {
            assertThrows(IllegalArgumentException.class, () -> client.setup(null));
            assertThrows(IllegalArgumentException.class, () -> client.setup(new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> client.verify(new byte[LelinkControlClient.MAX_BODY + 1]));
            assertEquals(0, socket.output.size());
            assertFalse(client.isClosed());
        }
        FakeSocket failed = new FakeSocket("");
        assertThrows(IOException.class, () -> LelinkControlClient.connect(endpoint(), value -> {
            throw new IOException("bind failed");
        }, 1000, () -> failed));
        assertTrue(failed.closed);
        assertNull(failed.target);
    }

    @Test public void absoluteDeadlineStopsSlowResponseDespiteIncomingBytes() throws Exception {
        try (FakeServer server = new FakeServer(socket -> {
            readRequest(socket.getInputStream());
            for (byte value : bytes(response(200, "ok"))) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
                Thread.sleep(60);
            }
        }); LelinkControlClient client = LelinkControlClient.connect(endpoint(), ignored -> {}, 250,
                () -> new RedirectSocket(server.port()))) {
            long started = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> client.setup(new byte[]{1}));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            assertTrue(client.isClosed());
        }
    }

    @Test public void cancellationClosesInFlightReadAndPreventsFurtherRequests() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try (FakeServer server = new FakeServer(socket -> {
            readRequest(socket.getInputStream());
            waiting.countDown();
            assertEquals(-1, socket.getInputStream().read());
        }); LelinkControlClient client = LelinkControlClient.connect(endpoint(), ignored -> {}, 2000,
                () -> new RedirectSocket(server.port()))) {
            Thread request = new Thread(() -> {
                try { client.setup(new byte[]{1}); }
                catch (Throwable error) { outcome.set(error); }
            });
            request.start();
            try {
                assertTrue(waiting.await(1, TimeUnit.SECONDS));
                client.close();
                request.join(1000);
                assertFalse(request.isAlive());
                assertTrue(outcome.get() instanceof IOException);
                assertThrows(IOException.class, () -> client.verify(new byte[]{2}));
            } finally {
                client.close();
                request.join(2500);
            }
        }
    }

    @Test public void deadlineAlsoInterruptsBlockedWrites() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        Socket socket = new Socket() {
            @Override public void connect(SocketAddress address, int timeout) {}
            @Override public void setTcpNoDelay(boolean enabled) {}
            @Override public void setSoTimeout(int timeout) {}
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        try {
                            if (!closed.await(2, TimeUnit.SECONDS)) fail("Write was not cancelled");
                        } catch (InterruptedException error) { throw new IOException(error); }
                        throw new IOException("Socket closed while writing");
                    }
                };
            }
            @Override public void close() { closed.countDown(); }
        };
        try (LelinkControlClient client = LelinkControlClient.connect(endpoint(), ignored -> {}, 100, () -> socket)) {
            assertThrows(SocketTimeoutException.class, () -> client.setup(new byte[]{1}));
            assertTrue(client.isClosed());
            assertEquals(0, closed.getCount());
        }
    }

    @Test public void encryptedCancellationUnblocksDecoderWithoutBufferRaces() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try (FakeServer server = new FakeServer(socket -> {
            try (LelinkSecureRecord.Decoder decoder = new LelinkSecureRecord.Decoder(new byte[32], new byte[8])) {
                String request = new String(decoder.read(socket.getInputStream()), StandardCharsets.US_ASCII);
                assertTrue(request.startsWith("GET /lelink-player-info HTTP/1.1"));
                waiting.countDown();
                assertEquals(-1, socket.getInputStream().read());
            }
        }); LelinkControlClient client = LelinkControlClient.connect(endpoint(), ignored -> {}, 2000,
                () -> new RedirectSocket(server.port()))) {
            client.enableEncryption(new byte[32], new byte[32]);
            Thread request = new Thread(() -> {
                try { client.readPlayerInfo(); }
                catch (Throwable error) { outcome.set(error); }
            });
            request.start();
            try {
                assertTrue(waiting.await(1, TimeUnit.SECONDS));
                client.close();
                request.join(1000);
                assertFalse(request.isAlive());
                assertTrue(outcome.get() instanceof IOException);
                assertTrue(client.isClosed());
            } finally {
                client.close();
                request.join(2500);
            }
        }
    }

    private static final class FakeSocket extends Socket {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InputStream input;
        boolean closed;
        SocketAddress target;
        int connects;
        FakeSocket(String response) {
            this(bytes(response));
        }
        FakeSocket(byte[] response) {
            input = new ByteArrayInputStream(response) {
                @Override public synchronized int read(byte[] target, int offset, int length) {
                    return super.read(target, offset, Math.min(length, 1));
                }
            };
        }
        @Override public void connect(SocketAddress address, int timeout) { target = address; connects++; }
        @Override public void setTcpNoDelay(boolean on) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void close() { closed = true; }
    }
    private static final class RedirectSocket extends Socket {
        final int port;
        RedirectSocket(int port) { this.port = port; }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout);
        }
    }
    private static void readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (header.size() < 2048) {
            int value = input.read();
            if (value < 0) throw new IOException("Request ended early");
            header.write(value);
            String text = header.toString(StandardCharsets.US_ASCII);
            if (text.endsWith("\r\n\r\n")) {
                int start = text.indexOf("Content-Length: ") + 16;
                int size = Integer.parseInt(text.substring(start, text.indexOf("\r\n", start)));
                if (input.readNBytes(size).length != size) throw new IOException("Request body ended early");
                return;
            }
        }
        throw new IOException("Request too large");
    }
    private static String headerValue(String request, String name) {
        String prefix = "\r\n" + name + ": ";
        int start = request.indexOf(prefix);
        assertTrue(start >= 0);
        start += prefix.length();
        return request.substring(start, request.indexOf("\r\n", start));
    }
    private interface Handler { void handle(Socket socket) throws Exception; }
    private static final class FakeServer implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        volatile Socket accepted;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        FakeServer(Handler handler) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(3000);
                    handler.handle(socket);
                } catch (IOException | InterruptedException ignored) {
                } catch (Throwable error) { failure.set(error); }
            });
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            server.close();
            if (accepted != null) accepted.close();
            thread.interrupt();
            thread.join(3000);
            assertFalse("Server should terminate", thread.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
