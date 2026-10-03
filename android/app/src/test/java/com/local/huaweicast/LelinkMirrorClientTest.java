package com.local.huaweicast;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSArray;
import com.dd.plist.PropertyListParser;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class LelinkMirrorClientTest {
    private static final byte[] SPS = {0x67, 0x42, 0, 0x1e, 0x11};
    private static final byte[] PPS = {0x68, 0x11};
    private static final byte[] KEY = frame(0x65, 97);
    private static final byte[] INTER = frame(0x41, 49);
    private static final String VIDEO = "<plist><dict><key>timing-port</key><integer>6002</integer>"
            + "<key>streams</key><array><dict><key>type</key><integer>97</integer>"
            + "<key>data-port</key><integer>6000</integer><key>udp-port</key><integer>6001</integer>"
            + "<key>max-seq-num</key><integer>10000</integer></dict></array></dict></plist>";
    private static final String AUDIO = "<plist><dict><key>streams</key><array><dict>"
            + "<key>type</key><integer>96</integer><key>ast</key><integer>1</integer>"
            + "<key>data-port</key><integer>6100</integer><key>control-port</key><integer>6101</integer>"
            + "</dict></array></dict></plist>";
    private static final LegacyMirrorClient.Binder BINDER = new LegacyMirrorClient.Binder() {
        public void bind(Socket socket) {}
        public void bind(DatagramSocket socket) {}
    };

    @Test public void negotiatesRawPortAndSendsClearConfigurationBeforeEncryptedKeyframe() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.configure(SPS, PPS);
            byte[] offered = KEY.clone();
            f.client.offer(offered, f.clock.epochNs() / 1000 + 123456, true);
            Arrays.fill(offered, (byte) 0);
            f.client.connect(f.endpoint, BINDER);
            await(() -> f.client.sentFrames() == 1);
            assertEquals(6000, f.tcp.destination.getPort());
            assertEquals(f.endpoint.address(), f.tcp.destination.getAddress());
            assertEquals(1, f.control.requests.size());
            NSDictionary setup = (NSDictionary) PropertyListParser.parse(f.control.requests.get(0));
            assertEquals("1", setup.objectForKey("mst").toString());
            assertEquals("1", setup.objectForKey("encrypt-mode").toString());
            assertEquals("7011", setup.objectForKey("timing-port").toString());
            byte[] expected = join(LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 960, 540, 123456),
                    new LelinkMediaWire.VideoEncryptor(f.control.seed, VideoCodec.H264).videoPacket(KEY, true, 960, 540, 123456));
            assertArrayEquals(expected, f.tcp.bytes());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void preservesChainedCipherAcrossResizeAndCopiesConfiguration() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            byte[] sps = SPS.clone(), pps = PPS.clone();
            f.client.configure(sps, pps);
            Arrays.fill(sps, (byte) 0);
            Arrays.fill(pps, (byte) 0);
            long epoch = f.clock.epochNs() / 1000;
            f.client.offer(KEY, epoch + 1000, true);
            await(() -> f.client.sentFrames() == 1);
            f.client.resize(540, 960);
            f.client.offer(INTER, epoch + 1500, false);
            f.client.configure(SPS, PPS);
            f.client.offer(INTER, epoch + 1800, false);
            f.client.offer(KEY, epoch + 2000, true);
            await(() -> f.client.sentFrames() == 2);
            try (LelinkMediaWire.VideoEncryptor cipher = new LelinkMediaWire.VideoEncryptor(f.control.seed, VideoCodec.H264)) {
                byte[] expected = join(
                        LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 960, 540, 1000),
                        cipher.videoPacket(KEY, true, 960, 540, 1000),
                        LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 540, 960, 2000),
                        cipher.videoPacket(KEY, true, 540, 960, 2000));
                assertArrayEquals(expected, f.tcp.bytes());
            }
            assertEquals(1, f.tcp.connections);
        }
    }

    @Test public void resizingClearsQueuedOldGenerationBeforeConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.configure(SPS, PPS);
            f.client.offer(KEY, f.clock.epochNs() / 1000 + 1000, true);
            f.client.resize(540, 960);
            f.client.configure(SPS, PPS);
            f.client.offer(KEY, f.clock.epochNs() / 1000 + 2000, true);
            f.client.connect(f.endpoint, BINDER);
            await(() -> f.client.sentFrames() == 1);
            byte[] config = LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 540, 960, 2000);
            assertArrayEquals(config, Arrays.copyOf(f.tcp.bytes(), config.length));
        }
    }

    @Test public void staleFrameIsDiscardedBeforeCipherConsumption() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.configure(SPS, PPS);
            f.client.offer(KEY, f.clock.epochNs() / 1000 + 1000, true);
            Thread.sleep(550);
            f.client.connect(f.endpoint, BINDER);
            await(() -> f.keyRequests.get() == 1);
            assertEquals(0, f.tcp.bytes().length);
            f.client.offer(INTER, f.clock.epochNs() / 1000 + 2000, false);
            f.client.offer(KEY, f.clock.epochNs() / 1000 + 3000, true);
            await(() -> f.client.sentFrames() == 1);
            try (LelinkMediaWire.VideoEncryptor cipher = new LelinkMediaWire.VideoEncryptor(f.control.seed, VideoCodec.H264)) {
                assertArrayEquals(join(LelinkMediaWire.codecPacket(VideoCodec.H264, null, SPS, PPS, 960, 540, 3000),
                        cipher.videoPacket(KEY, true, 960, 540, 3000)), f.tcp.bytes());
            }
        }
    }

    @Test public void boundedQueueRequestsNewKeyframeAndDropsFollowingInterframes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.configure(SPS, PPS);
            long epoch = f.clock.epochNs() / 1000;
            f.client.offer(KEY, epoch + 1, true);
            for (int i = 0; i < 15; i++) f.client.offer(INTER, epoch + i + 2, false);
            assertEquals(1, f.keyRequests.get());
            f.client.offer(KEY, epoch + 99, true);
            f.client.connect(f.endpoint, BINDER);
            await(() -> f.client.sentFrames() == 1);
            Thread.sleep(30);
            assertEquals(1, f.client.sentFrames());
        }
    }

    @Test public void nonFreeEndpointNeverStartsControlOrMedia() throws Exception {
        try (Fixture f = new Fixture()) {
            LelinkEndpoint password = endpoint(Map.of("lelinkport", bytes("7000"), "htv", bytes("2"), "atv", bytes("1")));
            assertThrows(IOException.class, () -> f.client.connect(password, BINDER));
            assertEquals(0, f.controlConnections.get());
            assertEquals(0, f.tcp.connections);
        }
    }

    @Test public void rejectedSetupClosesEntireSessionWithoutMediaConnection() throws Exception {
        for (int status : new int[]{401, 403, 600}) {
            try (Fixture f = new Fixture()) {
                f.control.status = status;
                assertThrows(IOException.class, () -> f.client.connect(f.endpoint, BINDER));
                assertTrue(f.control.closed);
                assertTrue(f.udp.closed);
                assertEquals(0, f.tcp.connections);
            }
        }
    }

    @Test public void invalidNegotiatedPortNeverStartsMedia() throws Exception {
        try (Fixture f = new Fixture()) {
            f.control.video = VIDEO.replace("6000", "0");
            assertThrows(IOException.class, () -> f.client.connect(f.endpoint, BINDER));
            assertEquals(0, f.tcp.connections);
            assertTrue(f.control.closed);
        }
    }

    @Test public void audioNegotiatesOnSharedControlAndInheritsTimingPort() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            byte[] seed = f.client.mediaSeed();
            Arrays.fill(seed, (byte) 0);
            assertArrayEquals(f.control.seed, f.client.mediaSeed());
            RaopAudioClient.NativePorts ports = f.client.setupAudio(44100, 8001, 8002);
            assertEquals(new RaopAudioClient.NativePorts(6100, 6101, 6002), ports);
            assertEquals(2, f.control.requests.size());
            NSDictionary setup = (NSDictionary) PropertyListParser.parse(f.control.requests.get(1));
            assertEquals("1", setup.objectForKey("ast").toString());
            assertEquals("8002", setup.objectForKey("timing-port").toString());
            assertEquals(1, f.controlConnections.get());
        }
    }

    @Test public void firstVideoTimingPortAnswersRaopBeforeSetupCompletesAndAfterAudioSetup() throws Exception {
        try (Fixture f = new Fixture()) {
            long origin = 0x1234567890abcdefL;
            long before = RaopAudioWire.ntpTimestampUs(f.clock.nowUs());
            f.control.beforeVideoResponse = () -> {
                f.udp.inject(RaopAudioWire.timingRequest(7, origin), f.endpoint.address(), 6200);
                try { await(() -> f.udp.replyCount() == 1); }
                catch (Exception error) { throw new AssertionError(error); }
            };
            f.client.connect(f.endpoint, BINDER);
            DatagramPacket first = f.udp.replyAt(0);
            assertEquals(6200, first.getPort());
            assertEquals(f.endpoint.address(), first.getAddress());
            var reply = RaopAudioWire.parseTimingReply(first.getData());
            assertEquals(7, reply.sequence());
            assertEquals(origin, reply.originNtp());
            assertTrue(reply.receiveNtp() >= before);
            assertTrue(reply.transmitNtp() >= reply.receiveNtp());
            assertTrue(reply.transmitNtp() <= RaopAudioWire.ntpTimestampUs(f.clock.nowUs()));
            f.client.setupAudio(44100, 8001, 8002);
            f.udp.inject(RaopAudioWire.timingRequest(8, origin + 1), f.endpoint.address(), 6200);
            await(() -> f.udp.replyCount() == 2 && f.client.timingReplies() == 2);
            assertEquals(8, RaopAudioWire.parseTimingReply(f.udp.replyAt(1).getData()).sequence());
            assertEquals(2, f.client.timingReplies());
        }
    }

    @Test public void timingPortDistinguishesBothFormatsAndIgnoresOtherPeersOrMalformedPackets() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            byte[] legacy = new byte[48];
            legacy[0] = 0x23;
            ByteBuffer.wrap(legacy).putLong(40, 0x1234567890abcdefL);
            f.udp.inject(legacy, f.endpoint.address(), 6201);
            await(() -> f.udp.replyCount() == 1);
            DatagramPacket reply = f.udp.replyAt(0);
            assertEquals(48, reply.getLength());
            assertEquals(6201, reply.getPort());
            assertEquals(0x24, reply.getData()[0]);
            assertEquals(0x1234567890abcdefL, ByteBuffer.wrap(reply.getData()).getLong(24));
            f.udp.inject(RaopAudioWire.timingRequest(9, 5), InetAddress.getByName("10.0.0.9"), 6201);
            byte[] malformed = RaopAudioWire.timingRequest(9, 5);
            malformed[1] = (byte) 0xd3;
            f.udp.inject(malformed, f.endpoint.address(), 6201);
            f.udp.inject(new byte[31], f.endpoint.address(), 6201);
            legacy[0] = 0x24;
            f.udp.inject(legacy, f.endpoint.address(), 6201);
            // A valid packet behind the rejected requests acts as an ordered receive barrier.
            f.udp.inject(RaopAudioWire.timingRequest(10, 6), f.endpoint.address(), 6202);
            await(() -> f.udp.replyCount() == 2);
            assertEquals(32, f.udp.replyAt(1).getLength());
            assertEquals(6202, f.udp.replyAt(1).getPort());
            assertEquals(10, RaopAudioWire.parseTimingReply(f.udp.replyAt(1).getData()).sequence());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void rejectedAudioClosesVideoAndControl() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.control.status = 403;
            assertThrows(IOException.class, () -> f.client.setupAudio(44100, 8001, 8002));
            assertTrue(f.tcp.closed);
            assertTrue(f.control.closed);
            assertTrue(f.udp.closed);
            assertThrows(IOException.class, () -> f.client.mediaSeed());
        }
    }

    @Test public void peerDisconnectFailsOnlyOnceAndClosesControl() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.eof();
            await(() -> f.failures.get() == 1);
            assertTrue(f.control.closed);
            assertTrue(f.udp.closed);
            assertTrue(f.control.teardowns.isEmpty());
            f.client.close();
            Thread.sleep(30);
            assertEquals(1, f.failures.get());
        }
    }

    @Test public void unexpectedMediaReplyStopsWithoutTryingLegacyHttp() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.reply('H');
            await(() -> f.failures.get() == 1);
            assertEquals(0, f.tcp.bytes().length);
            assertTrue(f.control.closed);
            assertTrue(f.tcp.closed);
        }
    }

    @Test public void blockedWriteTimesOutAndClosesWholeSession() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.blockWrites = true;
            f.client.configure(SPS, PPS);
            f.client.offer(KEY, System.nanoTime() / 1000, true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (f.failures.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, f.failures.get());
            assertTrue(f.control.closed);
            assertTrue(f.tcp.closed);
            assertEquals(0, f.client.sentFrames());
        }
    }

    @Test public void writeFailureIsTerminalAndCannotReconnect() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.tcp.writeFailure = true;
            f.client.configure(SPS, PPS);
            f.client.offer(KEY, System.nanoTime() / 1000, true);
            await(() -> f.failures.get() == 1);
            assertTrue(f.control.closed);
            assertTrue(f.tcp.closed);
            assertEquals(0, f.client.sentFrames());
            assertTrue(f.control.teardowns.isEmpty());
            assertThrows(IOException.class, () -> f.client.connect(f.endpoint, BINDER));
        }
    }

    @Test public void normalCloseTearsDownOnlyAcceptedVideoOnTheExistingControl() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.client.close();
            await(() -> f.control.closed);
            assertEquals(1, f.control.teardowns.size());
            assertEquals(97, teardownType(f.control.teardowns.get(0)));
            assertEquals(1, f.controlConnections.get());
            assertTrue(f.tcp.closed);
            assertTrue(f.udp.closed);
            f.client.close();
            assertEquals(1, f.control.teardowns.size());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void normalCloseTearsDownAcceptedAudioBeforeVideo() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.client.setupAudio(44100, 8001, 8002);
            f.client.close();
            await(() -> f.control.closed);
            assertEquals(2, f.control.teardowns.size());
            assertEquals(96, teardownType(f.control.teardowns.get(0)));
            assertEquals(97, teardownType(f.control.teardowns.get(1)));
            assertEquals(1, f.controlConnections.get());
        }
    }

    @Test public void cancellationDuringConnectDoesNotIssueTeardown() throws Exception {
        try (Fixture f = new Fixture()) {
            f.control.beforeVideoResponse = f.client::close;
            assertThrows(IOException.class, () -> f.client.connect(f.endpoint, BINDER));
            assertTrue(f.control.closed);
            assertTrue(f.control.teardowns.isEmpty());
            assertEquals(0, f.tcp.connections);
        }
    }

    @Test public void rejectedTeardownStillClosesAndDoesNotRetryOtherStreams() throws Exception {
        try (Fixture f = new Fixture()) {
            f.client.connect(f.endpoint, BINDER);
            f.client.setupAudio(44100, 8001, 8002);
            f.control.teardownStatus = 403;
            f.client.close();
            await(() -> f.control.closed);
            assertEquals(1, f.control.teardowns.size());
            assertEquals(96, teardownType(f.control.teardowns.get(0)));
            assertEquals(1, f.controlConnections.get());
            assertTrue(f.tcp.closed);
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void blockedTeardownReadOrWriteHasOneBudgetAndDoesNotBlockCaller() throws Exception {
        for (boolean blockWrite : new boolean[]{false, true}) {
            try (Fixture f = new Fixture()) {
                f.client.connect(f.endpoint, BINDER);
                f.client.setupAudio(44100, 8001, 8002);
                f.control.blockTeardownRead = !blockWrite;
                f.control.blockTeardownWrite = blockWrite;
                long started = System.nanoTime();
                f.client.close();
                assertTrue("Close blocked its caller", System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(150));
                await(() -> f.control.teardownEntered);
                await(() -> f.control.closed);
                assertTrue("Teardown exceeded its cleanup deadline", System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1500));
                assertEquals(1, f.controlConnections.get());
                assertTrue(f.control.teardowns.size() <= 1);
                assertTrue(f.tcp.closed);
                assertTrue(f.udp.closed);
                assertEquals(0, f.failures.get());
            }
        }
    }

    @Test public void validatesCodecAndDimensionsAndDoesNotNegotiateAfterClose() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.client.configureHevc(SPS, SPS, PPS));
            assertThrows(IllegalArgumentException.class, () -> f.client.configure(new byte[]{0x68, 1}, PPS));
            assertThrows(IllegalArgumentException.class, () -> f.client.resize(0, 540));
            assertThrows(IOException.class, () -> f.client.setupAudio(44100, 8001, 8002));
            assertThrows(IOException.class, () -> f.client.connect(f.endpoint, BINDER));
            assertEquals(0, f.controlConnections.get());
        }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static int teardownType(byte[] bytes) throws Exception {
        NSDictionary root = (NSDictionary) PropertyListParser.parse(bytes);
        assertEquals(1, root.count());
        NSArray streams = (NSArray) root.objectForKey("streams");
        assertEquals(1, streams.count());
        NSDictionary stream = (NSDictionary) streams.objectAtIndex(0);
        assertEquals(1, stream.count());
        return Integer.parseInt(stream.objectForKey("type").toString());
    }
    private static LelinkEndpoint endpoint(Map<String, byte[]> attributes) throws Exception {
        return LelinkEndpoint.from("H2", InetAddress.getByName("10.0.0.8"), 7000, attributes);
    }
    private static byte[] frame(int nal, int length) {
        byte[] frame = new byte[length];
        frame[3] = 1;
        frame[4] = (byte) nal;
        for (int i = 5; i < length; i++) frame[i] = (byte) (i + 7);
        return frame;
    }
    private static byte[] join(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.write(array);
        return out.toByteArray();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Condition did not complete", condition.getAsBoolean());
    }

    private static final class Fixture implements AutoCloseable {
        final FakeControl control = new FakeControl();
        final FakeSocket tcp = new FakeSocket();
        final FakeTiming udp = new FakeTiming();
        final AtomicInteger failures = new AtomicInteger(), keyRequests = new AtomicInteger(), controlConnections = new AtomicInteger();
        final LelinkEndpoint endpoint = endpoint(Map.of("lelinkport", bytes("7000"), "htv", bytes("1"), "atv", bytes("0")));
        final LegacyMirrorSession clock = LegacyMirrorSession.create();
        final LelinkMirrorClient client = new LelinkMirrorClient(960, 540, keyRequests::incrementAndGet,
                ignored -> failures.incrementAndGet(), clock, VideoCodec.H264, (endpoint, binder) -> {
                    controlConnections.incrementAndGet();
                    return control;
                }, new LelinkMirrorClient.SocketFactory() {
                    public Socket createSocket() { return tcp; }
                    public DatagramSocket createDatagramSocket() { return udp; }
                });
        Fixture() throws Exception {}
        public void close() { client.close(); tcp.close(); udp.close(); }
    }

    private static final class FakeControl implements LelinkMirrorClient.ControlSession {
        final byte[] seed = new byte[32];
        final List<byte[]> requests = new ArrayList<>();
        final List<byte[]> teardowns = new ArrayList<>();
        volatile boolean closed;
        volatile boolean teardownEntered;
        boolean blockTeardownRead, blockTeardownWrite;
        int status = 200;
        int teardownStatus = 200;
        String video = VIDEO;
        Runnable beforeVideoResponse = () -> {};
        FakeControl() { Arrays.fill(seed, (byte) 17); }
        public synchronized LelinkControlClient.Response setup(byte[] request) {
            requests.add(request.clone());
            if (requests.size() == 1) beforeVideoResponse.run();
            return new LelinkControlClient.Response(status, Map.of(), bytes(requests.size() == 1 ? video : AUDIO));
        }
        public LelinkControlClient.Response keepalive() { throw new AssertionError("Unexpected keepalive during short test"); }
        public synchronized LelinkControlClient.Response teardown(byte[] plist) throws IOException {
            if (closed) throw new IOException("Closed");
            teardownEntered = true;
            if (!blockTeardownWrite) teardowns.add(plist.clone());
            while ((blockTeardownRead || blockTeardownWrite) && !closed) {
                try { wait(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
            if (closed) throw new IOException("Closed");
            return new LelinkControlClient.Response(teardownStatus, Map.of(), new byte[0]);
        }
        public byte[] mediaSeed() { return seed.clone(); }
        public synchronized void close() { closed = true; notifyAll(); }
    }

    private static final class FakeSocket extends Socket {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile boolean closed, remoteEof, writeFailure, blockWrites;
        int incoming = -1;
        int connections;
        InetSocketAddress destination;
        @Override public void connect(SocketAddress address, int timeout) {
            destination = (InetSocketAddress) address;
            connections++;
        }
        @Override public void setTcpNoDelay(boolean enabled) {}
        @Override public void setSoTimeout(int timeout) {}
        @Override public InputStream getInputStream() {
            return new InputStream() {
                public int read() throws IOException {
                    synchronized (FakeSocket.this) {
                        if (closed || remoteEof) return -1;
                        if (incoming >= 0) { int result = incoming; incoming = -1; return result; }
                        try { FakeSocket.this.wait(50); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                        if (closed || remoteEof) return -1;
                        if (incoming >= 0) { int result = incoming; incoming = -1; return result; }
                        throw new SocketTimeoutException();
                    }
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    synchronized (FakeSocket.this) {
                        while (blockWrites && !closed) {
                            try { FakeSocket.this.wait(); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                        }
                        if (closed || writeFailure) throw new SocketException("Write failed");
                        output.write(bytes, offset, length);
                    }
                }
            };
        }
        synchronized byte[] bytes() { return output.toByteArray(); }
        synchronized void eof() { remoteEof = true; notifyAll(); }
        synchronized void reply(int value) { incoming = value; notifyAll(); }
        @Override public synchronized void close() { closed = true; notifyAll(); }
    }

    private static final class FakeTiming extends DatagramSocket {
        volatile boolean closed;
        final ArrayDeque<DatagramPacket> incoming = new ArrayDeque<>();
        final List<DatagramPacket> replies = new ArrayList<>();
        FakeTiming() throws SocketException { super((SocketAddress) null); }
        @Override public void bind(SocketAddress address) {}
        @Override public int getLocalPort() { return 7011; }
        @Override public synchronized void receive(DatagramPacket packet) throws IOException {
            if (closed) throw new SocketException("Closed");
            try { if (incoming.isEmpty()) wait(50); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            DatagramPacket next = incoming.poll();
            if (next != null) {
                int length = Math.min(packet.getLength(), next.getLength());
                System.arraycopy(next.getData(), 0, packet.getData(), packet.getOffset(), length);
                packet.setLength(length);
                packet.setAddress(next.getAddress());
                packet.setPort(next.getPort());
                return;
            }
            throw new SocketTimeoutException();
        }
        synchronized void inject(byte[] bytes, InetAddress address, int port) {
            incoming.add(new DatagramPacket(bytes.clone(), bytes.length, address, port));
            notifyAll();
        }
        @Override public synchronized void send(DatagramPacket packet) {
            byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
            replies.add(new DatagramPacket(data, data.length, packet.getAddress(), packet.getPort()));
        }
        synchronized int replyCount() { return replies.size(); }
        synchronized DatagramPacket replyAt(int index) { return replies.get(index); }
        @Override public synchronized void close() { closed = true; super.close(); notifyAll(); }
    }
}
