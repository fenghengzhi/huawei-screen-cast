package com.local.huaweicast;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class LegacyMirrorClientTest {
    private static final byte[] SPS = {0x67, 0x42, 0, 0x1e, 0x11};
    private static final byte[] PPS = {0x68, 0x11};
    private static final byte[] FRAME = {0, 0, 0, 1, 0x65, 0x11, 0x22};
    private static final String CAPABILITIES = "<plist><dict><key>width</key><integer>1920</integer><key>height</key><integer>1080</integer></dict></plist>";
    private static final LegacyMirrorClient.Binder BINDER = new LegacyMirrorClient.Binder() {
        public void bind(Socket socket) {}
        public void bind(DatagramSocket socket) {}
    };
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Condition did not complete", condition.getAsBoolean());
    }

    @Test public void writesPlistThenConfigurationAndAvccFrame() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.client.configure(SPS, PPS);
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
            byte[] bytes = f.tcp.bytes();
            String request = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue(request.startsWith("GET /stream.xml HTTP/1.1\r\nHost: 10.0.0.8:7100\r\n"));
            int post = request.indexOf("POST /stream HTTP/1.1");
            assertTrue(post > 0);
            assertTrue(request.substring(0, post).contains("Content-Length: 0\r\nConnection: keep-alive\r\n"));
            assertEquals(1, f.tcp.connections);
            int body = request.indexOf("\r\n\r\n", post) + 4;
            int lengthHeader = request.indexOf("Content-Length: ", post);
            int length = Integer.parseInt(request.substring(lengthHeader + 16, request.indexOf("\r\n", lengthHeader)));
            NSDictionary plist = (NSDictionary) PropertyListParser.parse(Arrays.copyOfRange(bytes, body, body + length));
            assertEquals("130.16", plist.objectForKey("version").toString());
            assertNotNull(plist.objectForKey("sessionID"));
            int packet = body + length;
            ByteBuffer wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, wire.getShort(packet + 4));
            assertEquals(960f, wire.getFloat(packet + 16), 0f);
            assertEquals(540f, wire.getFloat(packet + 20), 0f);
            packet += LegacyMirrorWire.HEADER_SIZE + wire.getInt(packet);
            assertEquals(0, wire.getShort(packet + 4));
            assertArrayEquals(new byte[]{0, 0, 0, 3, 0x65, 0x11, 0x22}, Arrays.copyOfRange(bytes, packet + 128, bytes.length));
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void preflightAuthorizationRejectionDoesNotSendPost() throws Exception {
        for (int status : new int[]{401, 403}) {
            try (Fixture f = new Fixture()) {
                f.tcp.input.clear();
                f.tcp.reply("HTTP/1.1 " + status + " Authorization required\r\nContent-Length: 0\r\n\r\n");
                try { f.client.connect(f.endpoint, BINDER); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("要求授权")); }
                assertFalse(new String(f.tcp.bytes(), StandardCharsets.US_ASCII).contains("POST /stream"));
                assertTrue(f.tcp.closed);
                assertTrue(f.udp.closed);
            }
        }
    }

    @Test public void invalidCapabilitiesDoNotStartStreaming() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.input.clear();
            f.tcp.reply("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n<plist/>");
            try { f.client.connect(f.endpoint, BINDER); fail(); }
            catch (IOException expected) {}
            assertFalse(new String(f.tcp.bytes(), StandardCharsets.US_ASCII).contains("POST /stream"));
            assertTrue(f.tcp.closed);
            assertTrue(f.udp.closed);
            assertEquals(0, f.client.sentFrames());
        }
    }

    @Test public void authorizationRejectionClosesBothSocketsExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.reply("HTTP/1.1 401 Unauthorized\r\n\r\n");
            await(() -> f.failures.get() == 1);
            assertTrue(f.message.contains("要求授权"));
            assertTrue(f.tcp.closed);
            assertTrue(f.udp.closed);
            f.client.close();
            Thread.sleep(100);
            assertEquals(1, f.failures.get());
        }
    }

    @Test public void connectionFailureCleansUpWithoutCallerCleanup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.connectFailure = true;
            try { f.client.connect(f.endpoint, BINDER); fail(); }
            catch (IOException expected) {}
            assertTrue(f.tcp.closed);
            assertTrue(f.udp.closed);
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void cancellationUnblocksHandshakeWriteWithoutFailureCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.blockWrites = true;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connect = worker.submit(() -> { try { f.client.connect(f.endpoint, BINDER); } catch (Exception expected) {} });
                assertTrue(f.tcp.writeEntered.await(1, TimeUnit.SECONDS));
                f.client.close();
                connect.get(1, TimeUnit.SECONDS);
                assertTrue(f.tcp.closed);
                assertTrue(f.udp.closed);
                assertEquals(0, f.failures.get());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void writeWatchdogClosesStalledHandshake() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.blockWrites = true;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connect = worker.submit(() -> { try { f.client.connect(f.endpoint, BINDER); } catch (Exception expected) {} });
                connect.get(8, TimeUnit.SECONDS);
                await(() -> f.failures.get() == 1);
                assertTrue(f.message.contains("写入超时"));
                assertTrue(f.udp.closed);
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void overflowDropsDependentFramesUntilNewKeyframe() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 9; i++) f.client.offer(FRAME, System.nanoTime() / 1000, true);
            assertEquals(1, f.keys.get());
            f.client.offer(FRAME, System.nanoTime() / 1000, false);
            f.client.connect(f.endpoint, BINDER);
            f.client.configure(SPS, PPS);
            Thread.sleep(100);
            assertEquals(0, f.client.sentFrames());
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
        }
    }

    @Test public void onlyValidPeerNtpQueriesIncreaseTimingEvidence() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            byte[] invalid = new byte[48]; invalid[0] = 0x1b;
            f.udp.requests.add(new DatagramPacket(invalid, 48, f.endpoint.address(), 30000));
            byte[] valid = new byte[48]; valid[0] = 0x23; valid[40] = 12;
            f.udp.requests.add(new DatagramPacket(valid, 48, InetAddress.getByAddress(new byte[]{10, 0, 0, 9}), 30000));
            f.udp.requests.add(new DatagramPacket(valid, 48, f.endpoint.address(), 30000));
            await(() -> f.client.timingReplies() == 1);
            assertEquals(1, f.udp.replies.size());
            byte[] reply = f.udp.replies.peek();
            assertEquals(0x24, reply[0]);
            assertEquals(12, reply[24]);
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void competingReadFailuresReportOneTerminalEvent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.udp.readFailure = true;
            f.tcp.input.add(-1);
            await(() -> f.failures.get() == 1);
            Thread.sleep(150);
            assertEquals(1, f.failures.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeSocket tcp = new FakeSocket();
        final FakeDatagramSocket udp = new FakeDatagramSocket();
        final AtomicInteger failures = new AtomicInteger(), keys = new AtomicInteger();
        volatile String message = "";
        final LelinkEndpoint endpoint = LelinkEndpoint.from("receiver", InetAddress.getByAddress(new byte[]{10, 0, 0, 8}), 7100,
                Map.of("mirror", "7100".getBytes(StandardCharsets.US_ASCII)));
        final LegacyMirrorClient client = new LegacyMirrorClient(960, 540, keys::incrementAndGet,
                text -> { message = text; failures.incrementAndGet(); }, new LegacyMirrorClient.SocketFactory() {
                    public Socket createSocket() { return tcp; }
                    public DatagramSocket createDatagramSocket() { return udp; }
                });
        Fixture() throws Exception {
            tcp.reply("HTTP/1.1 200 OK\r\nContent-Length: " + CAPABILITIES.length()
                    + "\r\nConnection: keep-alive\r\n\r\n" + CAPABILITIES);
        }
        public void close() { client.close(); }
    }

    private static final class FakeSocket extends Socket {
        final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        volatile boolean closed, blockWrites, connectFailure;
        volatile int connections;
        @Override public void connect(SocketAddress address, int timeout) throws IOException {
            connections++;
            if (connectFailure) throw new IOException("connection failed");
        }
        @Override public void setTcpNoDelay(boolean enabled) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public InputStream getInputStream() {
            return new InputStream() {
                public int read() throws IOException {
                    try {
                        Integer value = input.poll(50, TimeUnit.MILLISECONDS);
                        if (value != null) return value;
                        if (closed) return -1;
                        throw new SocketTimeoutException();
                    } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    synchronized (output) {
                        writeEntered.countDown();
                        while (blockWrites && !closed) {
                            try { output.wait(); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                        }
                        if (closed) throw new SocketException("closed");
                        output.write(bytes, offset, length);
                    }
                }
            };
        }
        void reply(String text) { for (byte value : text.getBytes(StandardCharsets.US_ASCII)) input.add(value & 255); }
        byte[] bytes() { synchronized (output) { return output.toByteArray(); } }
        @Override public void close() { closed = true; input.add(-1); synchronized (output) { output.notifyAll(); } }
    }

    private static final class FakeDatagramSocket extends DatagramSocket {
        final BlockingQueue<DatagramPacket> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
        volatile boolean closed, readFailure;
        FakeDatagramSocket() throws SocketException { super((SocketAddress) null); }
        @Override public void bind(SocketAddress address) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public void receive(DatagramPacket target) throws IOException {
            if (closed || readFailure) throw new SocketException("closed");
            try {
                DatagramPacket source = requests.poll(50, TimeUnit.MILLISECONDS);
                if (source == null) throw new SocketTimeoutException();
                System.arraycopy(source.getData(), source.getOffset(), target.getData(), target.getOffset(), source.getLength());
                target.setLength(source.getLength()); target.setAddress(source.getAddress()); target.setPort(source.getPort());
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
        @Override public void send(DatagramPacket packet) throws IOException {
            if (closed) throw new SocketException("closed");
            replies.add(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
        }
        @Override public void close() { closed = true; super.close(); }
    }
}
