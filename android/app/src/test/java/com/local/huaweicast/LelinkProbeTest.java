package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LelinkProbeTest {
    private static final String DICTIONARY = "<key>height</key><integer>1080</integer>"
            + "<key>width</key><integer>1920</integer><key>overscanned</key><false/>"
            + "<key>refreshRate</key><real>0.016666666666666666</real>"
            + "<key>version</key><string>375.3</string><key>happycast</key><integer>1.0</integer>";

    @Test public void recognizesObservedLegacyCapabilityWithoutFollowingExternalDtd() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://127.0.0.1:1/never-fetch.dtd\">"
                + plist(DICTIONARY);
        LelinkProbe.Capabilities result = LelinkProbe.parseCapabilities(bytes(xml));
        assertEquals(1920, result.width());
        assertEquals(1080, result.height());
    }

    @Test public void acceptsIntegralRealDimensionsOnly() throws Exception {
        var result = LelinkProbe.parseCapabilities(bytes(plist("<key>width</key><real>720.0000</real>"
                + "<key>height</key><string>1280</string>")));
        assertEquals(720, result.width());
        assertEquals(1280, result.height());
        for (String value : new String[]{"0", "8193", "-1", "NaN", "Infinity", "12.5", "1e3"}) {
            assertThrows(value, Exception.class, () -> LelinkProbe.parseCapabilities(bytes(plist(
                    "<key>width</key><real>" + value + "</real><key>height</key><integer>1080</integer>"))));
        }
    }

    @Test public void refusesMissingDuplicateAndStructuredDimensions() {
        for (String xml : new String[]{plist("<key>width</key><integer>1920</integer>"),
                plist(DICTIONARY + "<key>width</key><integer>100</integer>"),
                plist("<key>width</key><dict/><key>height</key><integer>1080</integer>"),
                "<root><dict>" + DICTIONARY + "</dict></root>",
                plist(DICTIONARY + "<key>orphan</key>")}) {
            assertThrows(Exception.class, () -> LelinkProbe.parseCapabilities(bytes(xml)));
        }
    }

    @Test public void refusesInternalAndExternalEntityDeclarations() {
        for (String declaration : new String[]{"<!ENTITY x '1920'>",
                "<!ENTITY x SYSTEM 'file:///etc/passwd'>",
                "<!ENTITY % x SYSTEM 'http://127.0.0.1:1/never-fetch'> %x;"}) {
            String xml = "<!DOCTYPE plist [" + declaration + "]>" + plist(DICTIONARY);
            assertThrows(Exception.class, () -> LelinkProbe.parseCapabilities(bytes(xml)));
        }
    }

    @Test public void refusesOversizedAndInvalidEncodingXml() {
        assertThrows(Exception.class, () -> LelinkProbe.parseCapabilities(new byte[LelinkProbe.MAX_BODY + 1]));
        assertThrows(Exception.class, () -> LelinkProbe.parseCapabilities(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(Exception.class, () -> LelinkProbe.parseCapabilities(new byte[]{0, '<', 'p'}));
    }

    @Test public void parsesBoundedContentLengthAndRtspWithoutWaitingForClose() throws Exception {
        var http = response("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcEXTRA", true, false);
        assertEquals(200, http.status());
        assertArrayEquals(bytes("abc"), http.body());
        var rtsp = response("RTSP/1.0 200 OK\r\nCSeq: 1\r\nServer: AirTunes\r\n\r\n", false, true);
        assertEquals(200, rtsp.status());
        assertEquals(0, rtsp.body().length);
    }

    @Test public void authorizationAndRedirectResponsesAreNotFollowedOrRead() throws Exception {
        for (int status : new int[]{301, 302, 401, 403}) {
            InputStream input = new ByteArrayInputStream(bytes("HTTP/1.1 " + status + " Refused\r\n"
                    + "Location: http://public.example/never-fetch\r\nContent-Length: 99999999\r\n\r\n")) {
                @Override public synchronized int read() {
                    if (available() == 0) fail("Must not read a rejected response body");
                    return super.read();
                }
            };
            assertEquals(status, LelinkProbe.readResponse(input, true, false).status());
        }
    }

    @Test public void refusesInvalidAndAmbiguousHttpFraming() {
        for (String headers : new String[]{"Content-Length: 32769\r\n", "Content-Length: -1\r\n",
                "Content-Length: 1\r\nContent-Length: 1\r\n",
                "Transfer-Encoding: chunked\r\nContent-Length: 1\r\n", "Transfer-Encoding: gzip\r\n",
                " Folded: invalid\r\n"}) {
            assertThrows(IOException.class, () -> response("HTTP/1.1 200 OK\r\n" + headers + "\r\n", true, false));
        }
        assertThrows(IOException.class, () -> response("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", true, false));
        assertThrows(IOException.class, () -> response("HTTP/1.1 200 OK\r\nX: " + "x".repeat(LelinkProbe.MAX_HEADERS)
                + "\r\n\r\n", true, false));
        assertThrows(IOException.class, () -> response("RTSP/1.0 200 OK\r\n\r\n", true, false));
    }

    @Test public void handlesBoundedChunksAndRejectsOversizedOrMalformedChunks() throws Exception {
        String prefix = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
        assertArrayEquals(bytes("abcdef"), response(prefix + "3\r\nabc\r\n3;x=y\r\ndef\r\n0\r\n\r\n", true, false).body());
        for (String chunks : new String[]{"8001\r\n", "ffffffff\r\n", "g\r\n", "3\r\nabcX\n", "0\r\n"}) {
            assertThrows(IOException.class, () -> response(prefix + chunks, true, false));
        }
    }

    @Test public void limitsBodiesWithoutContentLength() throws Exception {
        assertArrayEquals(bytes("data"), response("HTTP/1.0 200 OK\r\n\r\ndata", true, false).body());
        assertThrows(IOException.class, () -> response("HTTP/1.0 200 OK\r\n\r\n"
                + "x".repeat(LelinkProbe.MAX_BODY + 1), true, false));
    }

    @Test public void requestBindsBeforeConnectingAndSendsOnlyFixedCapabilityGet() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        try (FakeServer server = new FakeServer(socket -> {
            request.set(readRequest(socket.getInputStream()));
            socket.getOutputStream().write(bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"));
        }); LelinkProbe probe = new LelinkProbe(socket -> assertFalse(socket.isConnected()))) {
            var result = probe.request(InetAddress.getLoopbackAddress(), server.port(), false, deadline(3000));
            assertEquals(200, result.status());
            assertTrue(request.get().startsWith("GET /stream.xml HTTP/1.1\r\n"));
            assertFalse(request.get().contains("Authorization:"));
            assertArrayEquals(bytes("ok"), result.body());
        }
    }

    @Test public void absoluteDeadlineLimitsSlowTrickleResponses() throws Exception {
        try (FakeServer server = new FakeServer(socket -> {
            readRequest(socket.getInputStream());
            for (byte value : bytes("HTTP/1.1 200 OK\r\n\r\n")) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
                Thread.sleep(80);
            }
        }); LelinkProbe probe = new LelinkProbe(socket -> {})) {
            long started = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> probe.request(InetAddress.getLoopbackAddress(),
                    server.port(), false, deadline(250)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
        }
    }

    @Test public void cancellationClosesAnInFlightSocket() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try (FakeServer server = new FakeServer(socket -> {
            readRequest(socket.getInputStream());
            waiting.countDown();
            assertEquals(-1, socket.getInputStream().read());
        }); LelinkProbe probe = new LelinkProbe(socket -> {})) {
            Thread client = new Thread(() -> {
                try { probe.request(InetAddress.getLoopbackAddress(), server.port(), false, deadline(8000)); }
                catch (Throwable error) { outcome.set(error); }
            });
            client.start();
            try {
                assertTrue(waiting.await(2, TimeUnit.SECONDS));
                probe.close();
                client.join(1500);
                assertFalse(client.isAlive());
                assertTrue(outcome.get() instanceof IOException);
                assertThrows(IOException.class, () -> probe.request(InetAddress.getLoopbackAddress(), server.port(), false, deadline(1000)));
            } finally {
                probe.close();
                client.join(2500);
            }
        }
    }

    private static long deadline(int milliseconds) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds); }
    private static String plist(String dictionary) { return "<plist version=\"1.0\"><dict>" + dictionary + "</dict></plist>"; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static LelinkProbe.Response response(String value, boolean body, boolean rtsp) throws IOException {
        return LelinkProbe.readResponse(new ByteArrayInputStream(bytes(value)), body, rtsp);
    }
    private static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 1024) {
            int value = input.read();
            if (value < 0) throw new IOException("Request ended early");
            bytes.write(value);
            String text = bytes.toString(StandardCharsets.US_ASCII.name());
            if (text.endsWith("\r\n\r\n")) return text;
        }
        throw new IOException("Request too large");
    }
    private interface Handler { void handle(Socket socket) throws Exception; }
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile Socket accepted;
        FakeServer(Handler handler) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(3000);
                    handler.handle(socket);
                } catch (Exception ignored) {}
            });
            thread.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            server.close();
            if (accepted != null) accepted.close();
            thread.interrupt();
            thread.join(3000);
            assertFalse("Fake server should terminate", thread.isAlive());
        }
    }
}
