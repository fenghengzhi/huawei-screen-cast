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
    private static final byte[] HEVC_VPS = {0x40, 1, 12};
    private static final byte[] HEVC_SPS = {0x42, 1, 34};
    private static final byte[] HEVC_PPS = {0x44, 1, 56};
    private static final byte[] HEVC_FRAME = {0, 0, 0, 1, 0x26, 1, 78};
    private static final String CAPABILITIES = "<plist><dict><key>width</key><integer>1920</integer><key>height</key><integer>1080</integer></dict></plist>";
    private static final LegacyMirrorClient.Binder BINDER = new LegacyMirrorClient.Binder() {
        public void bind(Socket socket) {}
        public void bind(DatagramSocket socket) {}
    };
    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, 2);
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
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

    @Test public void videoUsesSharedAudioSessionClockAndNegotiatedLatency() throws Exception {
        LegacyMirrorSession session = new LegacyMirrorSession(0x020000000042L, 77, System.nanoTime() - TimeUnit.SECONDS.toNanos(5));
        try (Fixture f = new Fixture(); LegacyMirrorClient client = new LegacyMirrorClient(960, 540, () -> {}, message -> fail(message),
                session, 150, new LegacyMirrorClient.SocketFactory() {
                    public Socket createSocket() { return f.tcp; }
                    public DatagramSocket createDatagramSocket() { return f.udp; }
                })) {
            client.connect(f.endpoint, BINDER);
            client.configure(SPS, PPS);
            client.offer(FRAME, session.epochNs() / 1000 + 2_000_000, true);
            await(() -> client.sentFrames() == 1);
            byte[] bytes = f.tcp.bytes();
            String request = new String(bytes, StandardCharsets.ISO_8859_1);
            int post = request.indexOf("POST /stream HTTP/1.1");
            String identity = "X-Apple-Device-ID: 0x" + Long.toHexString(session.deviceId()) + "\r\n";
            assertTrue(request.substring(0, post).contains(identity));
            assertTrue(request.substring(post).contains(identity));
            assertTrue(request.substring(0, post).contains("X-Apple-ProtocolVersion: 0\r\n"));
            assertTrue(request.substring(0, post).contains("User-Agent: HuaweiCast-Experimental/1.0\r\n"));
            int body = request.indexOf("\r\n\r\n", post) + 4;
            int header = request.indexOf("Content-Length: ", post);
            int length = Integer.parseInt(request.substring(header + 16, request.indexOf("\r\n", header)));
            NSDictionary plist = (NSDictionary) PropertyListParser.parse(Arrays.copyOfRange(bytes, body, body + length));
            assertEquals("77", plist.objectForKey("sessionID").toString());
            assertEquals(Long.toString(session.deviceId()), plist.objectForKey("deviceID").toString());
            assertEquals("150", plist.objectForKey("latencyMs").toString());
            assertEquals(LegacyMirrorWire.ntpTimestamp(2_000_000), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(body + length + 8));
        }
    }

    @Test public void companionAudioProfileUsesBodylessPostBeforeVideoPackets() throws Exception {
        LegacyMirrorSession session = LegacyMirrorSession.create();
        try (Fixture f = new Fixture(); LegacyMirrorClient client = new LegacyMirrorClient(960, 540, () -> {},
                message -> fail(message), session, 100, true, new LegacyMirrorClient.SocketFactory() {
                    public Socket createSocket() { return f.tcp; }
                    public DatagramSocket createDatagramSocket() { return f.udp; }
                })) {
            client.connect(f.endpoint, BINDER);
            client.configure(SPS, PPS);
            client.offer(FRAME, session.epochNs() / 1000 + 2_000_000, true);
            await(() -> client.sentFrames() == 1);
            byte[] bytes = f.tcp.bytes();
            String request = new String(bytes, StandardCharsets.ISO_8859_1);
            int post = request.indexOf("POST /stream HTTP/1.1");
            int end = request.indexOf("\r\n\r\n", post) + 4;
            assertTrue(request.substring(0, post).contains("User-Agent: AirParrot/1.1 HuaweiCast/1.0\r\n"));
            assertTrue(request.substring(post, end).contains("User-Agent: AirParrot/1.1 HuaweiCast/1.0\r\n"));
            assertTrue(request.substring(post, end).contains("Content-Length: 0\r\n"));
            assertFalse(request.substring(post, end).contains("Content-Type:"));
            assertFalse(request.contains("bplist00"));
            assertEquals(1, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(end + 4));
            assertEquals(LegacyMirrorWire.ntpTimestamp(2_000_000), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(end + 8));
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
            f.client.configure(SPS, PPS);
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

    @Test public void successfulResponseDoesNotStopDisconnectMonitoringOrReconnect() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.reply("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nOK!");
            f.tcp.input.add(-1);
            await(() -> f.failures.get() == 1);
            assertTrue(f.message.contains("关闭了镜像连接"));
            assertTrue(f.udp.closed);
            assertEquals(1, f.tcp.connections);
            try { f.client.connect(f.endpoint, BINDER); fail(); }
            catch (IOException expected) {}
            assertEquals(1, f.tcp.connections);
        }
    }

    @Test public void laterAuthorizationRejectionStopsEstablishedSession() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.reply("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nHTTP/1.1 403 Forbidden\r\n\r\n");
            await(() -> f.failures.get() == 1);
            assertTrue(f.message.contains("要求授权"));
            assertEquals(1, f.tcp.connections);
        }
    }

    @Test public void incompleteResponseHasDeadlineEvenWhileHeartbeatsSucceed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.reply("HTTP/1.1 200");
            await(() -> f.failures.get() == 1, 8);
            assertTrue(f.message.contains("响应超时"));
            assertTrue(f.tcp.closed);
        }
    }

    @Test public void idleConnectionSendsHeartbeatWithoutClaimingVideoFrames() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            int before = f.tcp.bytes().length;
            await(() -> f.tcp.bytes().length >= before + LegacyMirrorWire.HEADER_SIZE);
            assertArrayEquals(LegacyMirrorWire.heartbeatPacket(), Arrays.copyOfRange(f.tcp.bytes(), before, before + LegacyMirrorWire.HEADER_SIZE));
            assertEquals(0, f.client.sentFrames());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void staleQueuedFramesAreDiscardedUntilFreshKeyframe() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.configure(SPS, PPS);
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            Thread.sleep(550);
            f.client.connect(f.endpoint, BINDER);
            await(() -> f.keys.get() == 1);
            assertEquals(0, f.client.sentFrames());
            f.client.offer(FRAME, System.nanoTime() / 1000, false);
            assertEquals(0, f.client.sentFrames());
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
        }
    }

    @Test public void resizeDiscardsOldQueuedConfigurationAndRequiresNewKeyframe() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.configure(SPS, PPS);
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            f.client.resize(540, 960);
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            f.client.configure(SPS, PPS);
            f.client.offer(FRAME, System.nanoTime() / 1000, false);
            f.client.connect(f.endpoint, BINDER);
            Thread.sleep(100);
            assertEquals(0, f.client.sentFrames());
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
            byte[] bytes = f.tcp.bytes();
            ByteBuffer wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int packet = firstPacket(bytes);
            assertEquals(1, wire.getShort(packet + 4));
            assertEquals(540f, wire.getFloat(packet + 16), 0f);
            assertEquals(960f, wire.getFloat(packet + 20), 0f);
            assertEquals(0f, wire.getFloat(packet + 56), 0f);
            assertEquals(0f, wire.getFloat(packet + 60), 0f);
        }
    }

    @Test public void liveResizeKeepsSameConnectionAndClock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.client.configure(SPS, PPS);
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
            byte[] initial = f.tcp.bytes();
            long firstPts = ByteBuffer.wrap(initial).order(ByteOrder.LITTLE_ENDIAN).getLong(firstPacket(initial) + 8);
            f.client.resize(540, 960);
            f.client.configure(SPS, PPS);
            Thread.sleep(10);
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 2);
            ByteBuffer wire = ByteBuffer.wrap(f.tcp.bytes()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, wire.getShort(initial.length + 4));
            assertEquals(540f, wire.getFloat(initial.length + 16), 0f);
            assertTrue(wire.getLong(initial.length + 8) > firstPts);
            assertEquals(1, f.tcp.connections);
        }
    }

    @Test public void hevcUsesPcProfileEvenWithoutAudioAndSendsConfigurationBeforeIdr() throws Exception {
        try (Fixture f = new Fixture(VideoCodec.H265)) {
            f.client.connect(f.endpoint, BINDER);
            f.client.configureHevc(HEVC_VPS, HEVC_SPS, HEVC_PPS);
            f.client.offer(HEVC_FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
            byte[] bytes = f.tcp.bytes();
            int packet = firstPacket(bytes);
            String handshake = new String(bytes, 0, packet, StandardCharsets.US_ASCII);
            assertTrue(handshake.contains("User-Agent: AirParrot/1.1 HuaweiCast/1.0"));
            assertFalse(handshake.contains("bplist00"));
            ByteBuffer wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(1, wire.getShort(packet + 4));
            byte[] config = LegacyMirrorWire.hevcConfiguration(HEVC_VPS, HEVC_SPS, HEVC_PPS);
            assertArrayEquals(config, Arrays.copyOfRange(bytes, packet + 128, packet + 128 + wire.getInt(packet)));
            long pts = wire.getLong(packet + 8);
            packet += 128 + wire.getInt(packet);
            assertEquals(0, wire.getShort(packet + 4));
            assertEquals(pts, wire.getLong(packet + 8));
            assertArrayEquals(new byte[]{0, 0, 0, 3, 0x46, 1, 0x50, 0, 0, 0, 3, 0x26, 1, 78},
                    Arrays.copyOfRange(bytes, packet + 128, bytes.length));
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void hevcResizeRequiresNewParameterSetsAndKeyframeOnSameConnection() throws Exception {
        try (Fixture f = new Fixture(VideoCodec.H265)) {
            f.client.connect(f.endpoint, BINDER);
            f.client.configureHevc(HEVC_VPS, HEVC_SPS, HEVC_PPS);
            f.client.offer(HEVC_FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 1);
            int before = f.tcp.bytes().length;
            f.client.resize(540, 960);
            f.client.offer(HEVC_FRAME, System.nanoTime() / 1000, true);
            byte[] changedSps = HEVC_SPS.clone(); changedSps[2] = 35;
            byte[] expectedConfig = LegacyMirrorWire.hevcConfiguration(HEVC_VPS, changedSps, HEVC_PPS);
            f.client.configureHevc(HEVC_VPS, changedSps, HEVC_PPS);
            changedSps[2] = 99;
            f.client.offer(HEVC_FRAME, System.nanoTime() / 1000, false);
            Thread.sleep(30);
            assertEquals(1, f.client.sentFrames());
            f.client.offer(HEVC_FRAME, System.nanoTime() / 1000, true);
            await(() -> f.client.sentFrames() == 2);
            byte[] bytes = f.tcp.bytes();
            ByteBuffer wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(540f, wire.getFloat(before + 40), 0f);
            assertEquals(960f, wire.getFloat(before + 44), 0f);
            assertArrayEquals(expectedConfig, Arrays.copyOfRange(bytes, before + 128, before + 128 + wire.getInt(before)));
            assertEquals(1, f.tcp.connections);
        }
    }

    @Test public void hevcRejectsWrongCodecAndOversizedParametersBeforeQueuingMedia() throws Exception {
        try (Fixture f = new Fixture(VideoCodec.H265)) {
            assertThrows(IllegalStateException.class, () -> f.client.configure(SPS, PPS));
            byte[] tooLarge = new byte[128]; tooLarge[0] = 0x40; tooLarge[1] = 1;
            assertThrows(IllegalArgumentException.class, () -> f.client.configureHevc(tooLarge, HEVC_SPS, HEVC_PPS));
            f.client.offer(HEVC_FRAME, System.nanoTime() / 1000, true);
            f.client.connect(f.endpoint, BINDER);
            Thread.sleep(30);
            assertEquals(0, f.client.sentFrames());
        }
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.client.configureHevc(HEVC_VPS, HEVC_SPS, HEVC_PPS));
        }
    }

    @Test public void cancellationUnblocksConnectWithoutFalseFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.blockConnect = true;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connect = worker.submit(() -> { try { f.client.connect(f.endpoint, BINDER); } catch (Exception expected) {} });
                assertTrue(f.tcp.connectEntered.await(1, TimeUnit.SECONDS));
                f.client.close();
                connect.get(1, TimeUnit.SECONDS);
                assertTrue(f.udp.closed);
                assertEquals(0, f.failures.get());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void cancellationUnblocksActiveWriterWithoutFalseFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.client.configure(SPS, PPS);
            f.tcp.blockWrites = true;
            f.client.offer(FRAME, System.nanoTime() / 1000, true);
            await(() -> f.tcp.writeBlocked);
            f.client.close();
            await(() -> !f.tcp.writeBlocked);
            assertEquals(0, f.failures.get());
            assertEquals(0, f.client.sentFrames());
        }
    }

    private static int firstPacket(byte[] bytes) {
        String request = new String(bytes, StandardCharsets.ISO_8859_1);
        int post = request.indexOf("POST /stream HTTP/1.1");
        int lengthHeader = request.indexOf("Content-Length: ", post);
        int length = Integer.parseInt(request.substring(lengthHeader + 16, request.indexOf("\r\n", lengthHeader)));
        return request.indexOf("\r\n\r\n", post) + 4 + length;
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

    @Test public void socketResetHasAnActionableDisconnectMessage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.readFailure = true;
            await(() -> f.failures.get() == 1);
            assertEquals("接收端已断开或网络连接中断", f.message);
            assertTrue(f.tcp.closed);
            assertTrue(f.udp.closed);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeSocket tcp = new FakeSocket();
        final FakeDatagramSocket udp = new FakeDatagramSocket();
        final AtomicInteger failures = new AtomicInteger(), keys = new AtomicInteger();
        volatile String message = "";
        final LelinkEndpoint endpoint = LelinkEndpoint.from("receiver", InetAddress.getByAddress(new byte[]{10, 0, 0, 8}), 7100,
                Map.of("mirror", "7100".getBytes(StandardCharsets.US_ASCII)));
        final LegacyMirrorClient client;
        Fixture() throws Exception { this(VideoCodec.H264); }
        Fixture(VideoCodec codec) throws Exception {
            client = new LegacyMirrorClient(960, 540, keys::incrementAndGet,
                text -> { message = text; failures.incrementAndGet(); }, null, 90, false, codec, new LegacyMirrorClient.SocketFactory() {
                    public Socket createSocket() { return tcp; }
                    public DatagramSocket createDatagramSocket() { return udp; }
                });
            tcp.reply("HTTP/1.1 200 OK\r\nContent-Length: " + CAPABILITIES.length()
                    + "\r\nConnection: keep-alive\r\n\r\n" + CAPABILITIES);
        }
        public void close() { client.close(); }
    }

    private static final class FakeSocket extends Socket {
        final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        final CountDownLatch connectEntered = new CountDownLatch(1);
        volatile boolean closed, blockWrites, connectFailure, blockConnect, writeBlocked, readFailure;
        volatile int connections;
        @Override public void connect(SocketAddress address, int timeout) throws IOException {
            connections++;
            connectEntered.countDown();
            synchronized (output) {
                while (blockConnect && !closed) {
                    try { output.wait(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                }
            }
            if (closed) throw new SocketException("closed");
            if (connectFailure) throw new IOException("connection failed");
        }
        @Override public void setTcpNoDelay(boolean enabled) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public InputStream getInputStream() {
            return new InputStream() {
                public int read() throws IOException {
                    if (readFailure) throw new SocketException("Connection reset");
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
                        try {
                            while (blockWrites && !closed) {
                                writeBlocked = true;
                                try { output.wait(); }
                                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                            }
                        } finally {
                            writeBlocked = false;
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
