package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

public class RaopAudioClientTest {
    private static final byte[] CONFIG = {(byte) 0xf8, (byte) 0xe8, 0x40, 0};
    private static final byte[] AAC = {1, 2, 3, 4};
    private static final InetAddress PEER;
    static { try { PEER = InetAddress.getByName("192.168.71.107"); } catch (Exception error) { throw new ExceptionInInitializerError(error); } }

    @Test public void handshakeUsesActualEldFormatAndOneSharedIdentityWithoutKeys() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(0, f.tcp.requests.size());
            f.connect();
            assertEquals(4, f.tcp.requests.size());
            String announce = f.tcp.requests.get(0);
            assertTrue(announce.startsWith("ANNOUNCE rtsp://192.168.71.107/" + f.session.sessionId()));
            assertTrue(announce.contains("X-LeLink-Device-ID: 0x" + Long.toHexString(f.session.deviceId())));
            assertFalse(announce.contains("X-Apple-Device-ID:"));
            assertTrue(announce.contains("User-Agent: AirParrot/1.1 HuaweiCast/1.0"));
            assertTrue(announce.contains("mpeg4-generic/44100/2"));
            assertTrue(announce.contains("mode=AAC-eld; constantDuration=512\r\n"));
            assertTrue(announce.contains("a=rtpmap:97 H264\r\n"));
            assertFalse(announce.contains("a=rtpmap:97 H265"));
            assertFalse(announce.contains("config="));
            assertFalse(announce.contains("fpaeskey"));
            assertFalse(announce.contains("aesiv"));
            assertTrue(f.tcp.requests.get(1).contains("/audio RTSP/1.0"));
            assertTrue(f.tcp.requests.get(1).contains("mode=screen;control_port="));
            assertTrue(f.tcp.requests.get(1).contains("Audio-Type: sample_rate=44100;channels=2\r\n"));
            assertTrue(f.tcp.requests.get(2).contains("/video RTSP/1.0"));
            assertTrue(f.tcp.requests.get(2).contains("Session: DEADBEEF\r\n"));
            assertTrue(f.tcp.requests.get(3).startsWith("RECORD "));
            assertTrue(f.tcp.requests.get(3).contains("RTP-Info: seq="));
            for (int index : new int[]{0, 2, 3}) assertFalse(f.tcp.requests.get(index).contains("Audio-Type:"));
            assertEquals(4, f.bindings.get());
            assertEquals(200, f.client.latencyMs());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void h265IsAnnouncedWithoutChangingAudioOrSessionProtocolHeaders() throws Exception {
        try (Fixture f = new Fixture(VideoCodec.H265)) {
            f.connect();
            String announce = f.tcp.requests.get(0);
            assertTrue(announce.contains("m=video 0 RTP/AVP 97\r\na=rtpmap:97 H265\r\n"));
            assertFalse(announce.contains("a=rtpmap:97 H264"));
            assertTrue(announce.contains("mpeg4-generic/44100/2"));
            assertTrue(announce.contains("mode=AAC-eld; constantDuration=512\r\n"));
            assertTrue(announce.contains("X-LeLink-Device-ID: 0x" + Long.toHexString(f.session.deviceId())));
            assertFalse(announce.contains("X-Apple-Device-ID:"));
            assertTrue(announce.contains("User-Agent: AirParrot/1.1 HuaweiCast/1.0"));
            assertTrue(f.tcp.requests.get(1).contains("Audio-Type: sample_rate=44100;channels=2\r\n"));
            assertTrue(f.tcp.requests.get(2).contains("Session: DEADBEEF\r\n"));
            assertEquals(4, f.tcp.requests.size());
        }
    }

    @Test public void explicitVideoCodecCannotBeNull() {
        try { new RaopAudioClient(LegacyMirrorSession.create(), 44100, 512, CONFIG, ignored -> {}, (VideoCodec) null); fail(); }
        catch (NullPointerException expected) { assertTrue(expected.getMessage().contains("video codec")); }
    }

    @Test public void nativeSetupBindsAllUdpSocketsWithoutLegacyControlRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger negotiations = new AtomicInteger();
            f.connectNative((controlPort, timingPort) -> {
                assertEquals(3, f.udps.size());
                assertEquals(3, f.bindings.get());
                assertEquals(f.udps.get(1).getLocalPort(), controlPort);
                assertEquals(f.udps.get(2).getLocalPort(), timingPort);
                for (FakeUdp udp : f.udps) { assertTrue(udp.bound); assertEquals(0, udp.getPort()); }
                negotiations.incrementAndGet();
                return new RaopAudioClient.NativePorts(6000, 6001, 6002);
            }, UnaryOperator.identity());
            assertEquals(1, negotiations.get());
            assertEquals(0, f.tcpAllocations.get());
            assertTrue(f.tcp.requests.isEmpty());
            assertFalse(f.client.negotiatedMirrorPort().isPresent());
            assertEquals(100, f.client.latencyMs());
            assertEquals(6000, f.udps.get(0).getPort());
            assertEquals(6001, f.udps.get(1).getPort());
            assertEquals(6002, f.udps.get(2).getPort());
            f.client.close(); f.client.close();
            for (FakeUdp udp : f.udps) assertTrue(udp.closed);
            assertTrue(f.tcp.requests.isEmpty());
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void nativeEncryptionPreservesSampleClockAndResendsIdenticalCiphertext() throws Exception {
        try (Fixture f = new Fixture(44100, 480, new byte[]{(byte) 0xf8, (byte) 0xe8, 0x50, 0})) {
            AtomicInteger encryptions = new AtomicInteger();
            f.connectNative((controlPort, timingPort) -> new RaopAudioClient.NativePorts(6000, 6001, 6002), raw -> {
                int nonce = encryptions.incrementAndGet();
                for (int i = 0; i < raw.length; i++) raw[i] ^= (byte) (0x60 + nonce);
                return raw;
            });
            long pts = System.nanoTime() / 1000;
            f.client.offer(AAC, pts); f.client.offer(AAC, pts + 1);
            await(() -> f.client.sentFrames() == 2);
            byte[] first = f.udps.get(0).sent.get(0), second = f.udps.get(0).sent.get(1);
            assertArrayEquals(new byte[]{0x60, 0x63, 0x62, 0x65}, Arrays.copyOfRange(first, 12, first.length));
            assertArrayEquals(new byte[]{0x63, 0x60, 0x61, 0x66}, Arrays.copyOfRange(second, 12, second.length));
            assertArrayEquals(new byte[]{1, 2, 3, 4}, AAC);
            ByteBuffer a = ByteBuffer.wrap(first), b = ByteBuffer.wrap(second);
            assertEquals(480, (b.getInt(4) - a.getInt(4)) & 0xffffffffL);
            assertEquals(0xe0, first[1] & 255); assertEquals(0x60, second[1] & 255);
            byte[] sync = f.udps.get(1).sent.get(0);
            assertEquals(RaopAudioWire.ntpTimestampUs(f.session.relativeUs(pts)), ByteBuffer.wrap(sync).getLong(8));
            assertEquals((a.getInt(4) - 4410L) & 0xffffffffL, ByteBuffer.wrap(sync).getInt(4) & 0xffffffffL);
            int sequence = a.getShort(2) & 65535;
            byte[] request = ByteBuffer.allocate(8).put((byte) 0x80).put((byte) 0xd5).putShort((short) 9)
                    .putShort((short) sequence).putShort((short) 1).array();
            f.udps.get(1).inject(request, PEER, 6001);
            await(() -> f.client.resentFrames() == 1);
            byte[] reply = f.udps.get(1).sent.get(1);
            assertArrayEquals(first, Arrays.copyOfRange(reply, 4, reply.length));
            assertEquals(2, encryptions.get());
            assertEquals(0, f.tcpAllocations.get());
        }
    }

    @Test public void nativeTimingStillValidatesNegotiatedPeerAndPort() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connectNative((controlPort, timingPort) -> new RaopAudioClient.NativePorts(6000, 6001, 6002), UnaryOperator.identity());
            FakeUdp timing = f.udps.get(2);
            byte[] request = RaopAudioWire.timingRequest(12, 34);
            timing.inject(request, InetAddress.getByName("192.168.71.8"), 6002);
            timing.inject(request, PEER, 6003);
            timing.inject(request, PEER, 6002);
            await(() -> f.client.timingReplies() == 1);
            assertEquals(1, timing.sent.size());
            assertEquals(34, RaopAudioWire.parseTimingReply(timing.sent.get(0)).originNtp());
        }
    }

    @Test public void nativeTimingAlsoAnswersOnlyValidNtpV4ClientRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connectNative((controlPort, timingPort) -> new RaopAudioClient.NativePorts(6000, 6001, 6002), UnaryOperator.identity());
            FakeUdp timing = f.udps.get(2);
            byte[] request = new byte[48]; request[0] = 0x23;
            ByteBuffer.wrap(request).putLong(40, 0x123456789abcdefL);
            timing.inject(request, InetAddress.getByName("192.168.71.8"), 6002);
            timing.inject(request, PEER, 6003);
            for (int firstByte : new int[]{0, 0x1b, 0x24, 0x22}) {
                byte[] invalid = request.clone(); invalid[0] = (byte) firstByte;
                timing.inject(invalid, PEER, 6002);
            }
            timing.inject(Arrays.copyOf(request, 47), PEER, 6002);
            timing.inject(Arrays.copyOf(request, 49), PEER, 6002);
            timing.inject(request, PEER, 6002);
            await(() -> f.client.timingReplies() == 1);
            assertEquals(1, timing.sent.size());
            byte[] reply = timing.sent.get(0);
            assertEquals(48, reply.length); assertEquals(0x24, reply[0]);
            ByteBuffer fields = ByteBuffer.wrap(reply);
            assertEquals(0x123456789abcdefL, fields.getLong(24));
            assertTrue(Long.compareUnsigned(fields.getLong(40), fields.getLong(32)) >= 0);
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void legacyTimingIgnoresNativeNtpWithoutAProtocolFallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect();
            FakeUdp timing = f.udps.get(2);
            byte[] nativeRequest = new byte[48]; nativeRequest[0] = 0x23;
            timing.inject(nativeRequest, PEER, 6002);
            timing.inject(RaopAudioWire.timingRequest(12, 34), PEER, 6002);
            await(() -> f.client.timingReplies() == 1);
            assertEquals(1, timing.sent.size());
            assertEquals(32, timing.sent.get(0).length);
            assertEquals(34, RaopAudioWire.parseTimingReply(timing.sent.get(0)).originNtp());
        }
    }

    @Test public void nativeEndpointValidationPrecedesSocketAllocationAndSetup() throws Exception {
        for (String address : new String[]{"0.0.0.0", "127.0.0.1", "8.8.8.8", "224.0.0.1"}) {
            try (Fixture f = new Fixture()) {
                try {
                    f.client.connectNative(InetAddress.getByName(address), f.binder(), (controlPort, timingPort) -> {
                        fail("Invalid peer must not negotiate"); return null;
                    }, UnaryOperator.identity());
                    fail();
                } catch (IOException expected) {}
                assertTrue(f.udps.isEmpty()); assertEquals(0, f.tcpAllocations.get());
            }
        }
    }

    @Test public void nativeInvalidNegotiatedPortsCloseEveryUdpWithoutSending() throws Exception {
        for (RaopAudioClient.NativePorts ports : new RaopAudioClient.NativePorts[]{null,
                new RaopAudioClient.NativePorts(0, 6001, 6002), new RaopAudioClient.NativePorts(65536, 6001, 6002),
                new RaopAudioClient.NativePorts(6000, -1, 6002), new RaopAudioClient.NativePorts(6000, 65536, 6002),
                new RaopAudioClient.NativePorts(6000, 6001, 0), new RaopAudioClient.NativePorts(6000, 6001, 65536)}) {
            try (Fixture f = new Fixture()) {
                try { f.connectNative((controlPort, timingPort) -> ports, UnaryOperator.identity()); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("端口")); }
                assertEquals(3, f.udps.size());
                for (FakeUdp udp : f.udps) { assertTrue(udp.closed); assertTrue(udp.sent.isEmpty()); }
                assertEquals(0, f.tcpAllocations.get()); assertEquals(0, f.failures.get());
            }
        }
    }

    @Test public void nativeSetupFailureDoesNotRetryOrOpenLegacyControl() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger negotiations = new AtomicInteger();
            try {
                f.connectNative((controlPort, timingPort) -> {
                    negotiations.incrementAndGet(); throw new RaopAudioClient.AuthorizationRequiredException();
                }, UnaryOperator.identity());
                fail();
            } catch (RaopAudioClient.AuthorizationRequiredException expected) {}
            assertEquals(1, negotiations.get()); assertEquals(0, f.tcpAllocations.get());
            for (FakeUdp udp : f.udps) assertTrue(udp.closed);
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void nativeCancellationDuringNegotiationCannotRestartClosedSockets() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connect = worker.submit(() -> {
                    try {
                        f.connectNative((controlPort, timingPort) -> {
                            entered.countDown();
                            try { if (!release.await(1, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for cancellation"); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                            return new RaopAudioClient.NativePorts(6000, 6001, 6002);
                        }, UnaryOperator.identity());
                        fail("Cancelled setup must fail");
                    } catch (IOException expected) {}
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                f.client.close(); release.countDown(); connect.get(1, TimeUnit.SECONDS);
                for (FakeUdp udp : f.udps) { assertTrue(udp.closed); assertTrue(udp.sent.isEmpty()); }
                assertEquals(0, f.tcpAllocations.get()); assertEquals(0, f.failures.get());
            } finally { release.countDown(); worker.shutdownNow(); }
        }
    }

    @Test public void nativeCancellationDuringUdpAllocationClosesUnassignedSocket() throws Exception {
        try (Fixture f = new Fixture()) {
            f.afterUdpAllocation = () -> { if (f.udps.size() == 2) f.client.close(); };
            try {
                f.connectNative((controlPort, timingPort) -> {
                    fail("Cancelled allocation must not negotiate"); return null;
                }, UnaryOperator.identity());
                fail();
            } catch (IOException expected) {}
            assertEquals(2, f.udps.size());
            for (FakeUdp udp : f.udps) assertTrue(udp.closed);
            assertEquals(0, f.tcpAllocations.get()); assertEquals(0, f.failures.get());
        }
    }

    @Test public void nativeInvalidEncryptionStopsWithoutSendingPlaintext() throws Exception {
        for (byte[] encrypted : new byte[][]{null, new byte[0], new byte[8193]}) {
            try (Fixture f = new Fixture()) {
                f.connectNative((controlPort, timingPort) -> new RaopAudioClient.NativePorts(6000, 6001, 6002), raw -> encrypted);
                f.client.offer(AAC, System.nanoTime() / 1000);
                await(() -> f.failures.get() == 1);
                assertTrue(f.message.contains("加密"));
                assertTrue(f.udps.get(0).sent.isEmpty());
                for (FakeUdp udp : f.udps) assertTrue(udp.closed);
            }
        }
    }

    @Test public void audioSetupAdvertisesActualSampleRateWithoutHardcoding44100() {
        for (int rate : new int[]{44100, 48000}) {
            Map<String, String> headers = RaopAudioClient.audioSetupHeaders(rate, 40101, 40102);
            assertEquals("sample_rate=" + rate + ";channels=2", headers.get("Audio-Type"));
            assertTrue(headers.get("Audio-Type").length() >= 20);
            assertEquals("RTP/AVP/UDP;unicast;mode=screen;control_port=40101;timing_port=40102", headers.get("Transport"));
        }
    }

    @Test public void rejectsNonEldEncoderInsteadOfMislabelingIt() {
        try { new RaopAudioClient(new LegacyMirrorSession(123, 456, System.nanoTime()), 44100, 512, new byte[]{0x12, 0x10}, ignored -> {}); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("AAC-ELD")); }
    }

    @Test public void declaredRateAndFrameDurationMustMatchTheEncoderConfiguration() {
        for (int[] declared : new int[][]{{48000, 512}, {44100, 480}}) {
            try { new RaopAudioClient(LegacyMirrorSession.create(), declared[0], declared[1], CONFIG, ignored -> {}); fail(); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("AudioSpecificConfig")); }
        }
    }

    @Test public void publicNetworkEndpointIsRejectedBeforeOpeningSockets() throws Exception {
        try (Fixture f = new Fixture()) {
            try { f.client.connect(InetAddress.getByName("8.8.8.8"), 52244, new LegacyMirrorClient.Binder() {
                public void bind(Socket socket) { fail(); }
                public void bind(DatagramSocket socket) { fail(); }
            }); fail(); } catch (IOException expected) {}
            assertTrue(f.udps.isEmpty()); assertTrue(f.tcp.requests.isEmpty());
        }
    }

    @Test public void negotiatedVideoPortIsReportedAndCannotRedirectOrBeMalformed() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.videoTransport = "RTP/AVP/TCP;unicast;mode=record;server_port=7001";
            f.connect(); assertEquals(7001, f.client.negotiatedMirrorPort().orElseThrow());
        }
        for (String transport : new String[]{
                "RTP/AVP/TCP;unicast;server_port=0", "RTP/AVP/TCP;unicast;server_port=65536",
                "RTP/AVP/TCP;unicast;server_port=7100;source=8.8.8.8", "RTP/AVP/UDP;unicast;server_port=7100"}) {
            try (Fixture f = new Fixture()) {
                f.tcp.videoTransport = transport;
                try { f.connect(); fail(); } catch (IOException expected) {}
                assertEquals(3, f.tcp.requests.size()); assertTrue(f.tcp.closed);
            }
        }
    }

    @Test public void authorizationStopsWithoutMoreRequestsOrRetry() throws Exception {
        for (int status : new int[]{401, 403}) {
            try (Fixture f = new Fixture()) {
                f.tcp.rejectAt = 1; f.tcp.rejectStatus = status;
                try { f.connect(); fail(); } catch (RaopAudioClient.AuthorizationRequiredException expected) {}
                assertEquals(1, f.tcp.requests.size());
                assertTrue(f.tcp.closed);
                for (FakeUdp udp : f.udps) assertTrue(udp.closed);
                assertEquals(0, f.client.sentFrames());
                assertEquals(0, f.failures.get());
            }
        }
    }

    @Test public void laterAuthorizationAlsoStopsWithoutTeardownOrVolumeChange() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.rejectAt = 4; f.tcp.rejectStatus = 403;
            try { f.connect(); fail(); } catch (RaopAudioClient.AuthorizationRequiredException expected) {}
            assertEquals(4, f.tcp.requests.size());
            assertTrue(f.tcp.closed);
        }
    }

    @Test public void responseSequenceAndFramingAreValidated() throws Exception {
        for (String response : new String[]{
                "RTSP/1.0 200 OK\r\nCSeq: 999\r\n\r\n",
                "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n",
                "RTSP/1.0 200 OK\r\nCSeq: 1\r\nTransfer-Encoding: chunked\r\n\r\n",
                "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 32769\r\n\r\n",
                "RTSP/1.0 200 OK\r\nCSeq: 1\r\nX-Huge: " + "x".repeat(8200) + "\r\n\r\n"}) {
            try (Fixture f = new Fixture()) {
                f.tcp.firstResponse = response;
                try { f.connect(); fail(); } catch (IOException expected) {}
                assertEquals(1, f.tcp.requests.size());
                assertTrue(f.tcp.closed);
            }
        }
    }

    @Test public void negotiatedAddressesAndPortsCannotRedirectTheStream() throws Exception {
        for (String transport : new String[]{
                "RTP/AVP/UDP;unicast;server_port=0;control_port=6001;timing_port=6002",
                "RTP/AVP/UDP;unicast;server_port=6000;control_port=999999;timing_port=6002",
                "RTP/AVP/UDP;unicast;server_port=6000-6001;control_port=6001;timing_port=6002",
                "RTP/AVP/UDP;unicast;server_port=6000;control_port=6001;timing_port=6002;source=8.8.8.8",
                "RTP/AVP/TCP;unicast;server_port=6000;control_port=6001;timing_port=6002",
                "RTP/AVP/UDP;multicast;server_port=6000;control_port=6001;timing_port=6002"}) {
            try (Fixture f = new Fixture()) {
                f.tcp.transport = transport;
                try { f.connect(); fail(); } catch (IOException expected) {}
                assertEquals(2, f.tcp.requests.size());
                for (FakeUdp udp : f.udps) assertEquals(0, udp.sent.size());
            }
        }
    }

    @Test public void missingAndChangedSessionTokensAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.session = "";
            try { f.connect(); fail(); } catch (IOException expected) {}
            assertEquals(2, f.tcp.requests.size());
        }
        try (Fixture f = new Fixture()) {
            f.tcp.changedSession = true;
            try { f.connect(); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("会话")); }
            assertEquals(3, f.tcp.requests.size());
        }
    }

    @Test public void keepaliveHonorsAShortReceiverSessionTimeout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.session = "DEADBEEF;timeout=1";
            f.connect();
            await(() -> f.tcp.requests.size() >= 5);
            assertTrue(f.tcp.requests.get(4).startsWith("OPTIONS * RTSP/1.0"));
            assertTrue(f.tcp.requests.get(4).contains("Session: DEADBEEF\r\n"));
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void rawAudioAndSyncUseActualSamplesAndSharedClock() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect();
            long pts = System.nanoTime() / 1000;
            byte[] mutable = AAC.clone();
            f.client.offer(mutable, pts); mutable[0] = 99;
            f.client.offer(AAC, pts + Math.round(512_000_000.0 / 44100));
            await(() -> f.client.sentFrames() == 2);
            byte[] first = f.udps.get(0).sent.get(0), second = f.udps.get(0).sent.get(1);
            assertArrayEquals(AAC, Arrays.copyOfRange(first, 12, first.length));
            assertEquals(0xe0, first[1] & 255); assertEquals(0x60, second[1] & 255);
            ByteBuffer a = ByteBuffer.wrap(first), b = ByteBuffer.wrap(second);
            assertEquals(512, (b.getInt(4) - a.getInt(4)) & 0xffffffffL);
            assertEquals(((a.getShort(2) & 65535) + 1) & 65535, b.getShort(2) & 65535);
            byte[] sync = f.udps.get(1).sent.get(0);
            ByteBuffer clock = ByteBuffer.wrap(sync);
            assertEquals(0x90, sync[0] & 255);
            assertEquals(RaopAudioWire.ntpTimestampUs(f.session.relativeUs(pts)), clock.getLong(8));
            assertEquals((a.getInt(4) - 8820L) & 0xffffffffL, clock.getInt(4) & 0xffffffffL);
            assertEquals(a.getInt(4), clock.getInt(16));
        }
    }

    @Test public void staleFramesAreDroppedButTimestampCorrectionDoesNotDiscardAudio() throws Exception {
        try (Fixture f = new Fixture()) {
            long stale = System.nanoTime() / 1000;
            f.client.offer(AAC, stale); Thread.sleep(380);
            f.connect();
            await(() -> f.client.droppedFrames() == 1);
            assertEquals(0, f.client.sentFrames());
            long pts = System.nanoTime() / 1000;
            f.client.offer(AAC, pts); await(() -> f.client.sentFrames() == 1);
            f.client.offer(AAC, pts - 1);
            await(() -> f.client.sentFrames() == 2);
            assertEquals(1, f.client.droppedFrames());
            assertEquals(1, f.client.nonIncreasingPts());
            assertEquals(1, f.client.nearDuplicatePts());
        }
    }

    @Test public void jitteringPtsNeverDropsOrRepeatsAValid480SampleAccessUnit() throws Exception {
        try (Fixture f = new Fixture(44100, 480, new byte[]{(byte) 0xf8, (byte) 0xe8, 0x50, 0})) {
            f.connect();
            long start = System.nanoTime() / 1000;
            long[] offsets = {0, 10884, 10885, 21768, 21768, 21000, 32652, 55000, 55001, 65900};
            for (long offset : offsets) f.client.offer(AAC, start + offset);
            await(() -> f.client.sentFrames() == offsets.length);
            assertEquals(offsets.length, f.client.offeredFrames());
            assertEquals(0, f.client.droppedFrames());
            assertEquals(2, f.client.nonIncreasingPts());
            assertTrue(f.client.nearDuplicatePts() >= 4);
            List<byte[]> packets = f.udps.get(0).sent;
            long base = ByteBuffer.wrap(packets.get(0)).getInt(4) & 0xffffffffL;
            int firstSequence = ByteBuffer.wrap(packets.get(0)).getShort(2) & 65535;
            for (int i = 0; i < offsets.length; i++) {
                ByteBuffer packet = ByteBuffer.wrap(packets.get(i));
                assertEquals((base + i * 480) & 0xffffffffL, packet.getInt(4) & 0xffffffffL);
                assertEquals((firstSequence + i) & 65535, packet.getShort(2) & 65535);
            }
        }
    }

    @Test public void sustainedTimestampCorrectionsDoNotAccumulateMissingAudio() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect(); long start = System.nanoTime() / 1000;
            for (int i = 0; i < 180; i++) {
                long nominal = Math.round(i * 512_000_000.0 / 44100);
                long correction = (i % 3 == 2) ? -11000 : 0;
                f.client.offer(AAC, start + nominal + correction);
                final long count = i + 1;
                await(() -> f.client.sentFrames() == count);
            }
            assertEquals(180, f.client.sentFrames()); assertEquals(0, f.client.droppedFrames());
            assertTrue(f.client.nearDuplicatePts() >= 60);
            List<byte[]> packets = f.udps.get(0).sent;
            long first = ByteBuffer.wrap(packets.get(0)).getInt(4) & 0xffffffffL;
            long last = ByteBuffer.wrap(packets.get(packets.size() - 1)).getInt(4) & 0xffffffffL;
            assertEquals(179L * 512, (last - first) & 0xffffffffL);
        }
    }

    @Test public void actualDroppedAccessUnitsRetainSampleClockGapAndDistinctDiagnostics() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect(); long pts = System.nanoTime() / 1000;
            f.client.offer(AAC, pts); await(() -> f.client.sentFrames() == 1);
            f.client.offer(new byte[8193], pts + 11610);
            f.client.offer(AAC, pts + 23220); await(() -> f.client.sentFrames() == 2);
            byte[] first = f.udps.get(0).sent.get(0), last = f.udps.get(0).sent.get(1);
            assertEquals(1024, (ByteBuffer.wrap(last).getInt(4) - ByteBuffer.wrap(first).getInt(4)) & 0xffffffffL);
            assertEquals(3, f.client.offeredFrames()); assertEquals(1, f.client.droppedFrames());
            assertTrue(f.client.diagnostics().contains("oversize=1"));
            assertTrue(f.client.diagnostics().contains("queue=0,stale=0"));
        }
    }

    @Test public void overflowAndOversizeHaveBoundedMemory() throws Exception {
        try (Fixture f = new Fixture()) {
            long pts = System.nanoTime() / 1000;
            for (int i = 0; i < 33; i++) f.client.offer(AAC, pts + i * 11610L);
            assertEquals(32, f.client.droppedFrames());
            f.client.offer(new byte[8193], pts);
            assertEquals(33, f.client.droppedFrames());
            f.connect(); await(() -> f.client.sentFrames() == 1);
            assertEquals(1, f.udps.get(0).sent.size());
        }
    }

    @Test public void timingOnlyAnswersTheNegotiatedPeerAndWellFormedRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect();
            FakeUdp udp = f.udps.get(2);
            byte[] request = RaopAudioWire.timingRequest(123, 456);
            udp.inject(request, InetAddress.getByName("192.168.71.8"), 6002);
            udp.inject(request, PEER, 6003);
            udp.inject(new byte[48], PEER, 6002);
            udp.inject(request, PEER, 6002);
            await(() -> f.client.timingReplies() == 1);
            assertEquals(1, udp.sent.size());
            RaopAudioWire.TimingReply reply = RaopAudioWire.parseTimingReply(udp.sent.get(0));
            assertEquals(123, reply.sequence()); assertEquals(456, reply.originNtp());
            assertTrue(Long.compareUnsigned(reply.transmitNtp(), reply.receiveNtp()) >= 0);
        }
    }

    @Test public void retransmitIsExactAndBoundsRequestCountAndRate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect(); f.client.offer(AAC, System.nanoTime() / 1000);
            await(() -> f.client.sentFrames() == 1);
            byte[] original = f.udps.get(0).sent.get(0);
            int sequence = ByteBuffer.wrap(original).getShort(2) & 65535;
            FakeUdp control = f.udps.get(1);
            byte[] tooMany = ByteBuffer.allocate(8).put((byte) 0x80).put((byte) 0xd5).putShort((short) 9).putShort((short) sequence).putShort((short) 33).array();
            control.inject(tooMany, PEER, 6001);
            byte[] valid = tooMany.clone(); valid[7] = 1;
            for (int i = 0; i < 140; i++) control.inject(valid, PEER, 6001);
            await(() -> f.client.resentFrames() == 128);
            Thread.sleep(50);
            assertEquals(128, f.client.resentFrames());
            byte[] reply = control.sent.get(1);
            assertEquals(0xd6, reply[1] & 255);
            assertArrayEquals(original, Arrays.copyOfRange(reply, 4, reply.length));
        }
    }

    @Test public void closeSendsSingleBoundedTeardownAndClosesAllSockets() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect(); f.client.close(); f.client.close();
            await(() -> f.tcp.closed);
            assertEquals(5, f.tcp.requests.size());
            assertTrue(f.tcp.requests.get(4).startsWith("TEARDOWN "));
            assertTrue(f.tcp.requests.get(4).contains("Session: DEADBEEF\r\n"));
            for (FakeUdp udp : f.udps) assertTrue(udp.closed);
            assertEquals(0, f.failures.get());
        }
    }

    @Test public void cancellationUnblocksHandshakeWriteWithoutFailureCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.blockWrites = true;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connect = worker.submit(() -> { try { f.connect(); } catch (IOException ignored) {} });
                assertTrue(f.tcp.writeEntered.await(1, TimeUnit.SECONDS));
                f.client.close(); connect.get(1, TimeUnit.SECONDS);
                assertTrue(f.tcp.closed);
                for (FakeUdp udp : f.udps) assertTrue(udp.closed);
                assertEquals(0, f.failures.get());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void watchdogBoundsHandshakeWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.blockWrites = true;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> connect = worker.submit(() -> { try { f.connect(); } catch (IOException ignored) {} });
                connect.get(6, TimeUnit.SECONDS);
                await(() -> f.failures.get() == 1);
                assertTrue(f.tcp.closed);
                assertTrue(f.message.contains("超时"));
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void incompleteResponseHasDeadline() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tcp.firstResponse = "RTSP/1.0 200";
            long start = System.nanoTime();
            try { f.connect(); fail(); } catch (IOException expected) {}
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 6);
            assertTrue(f.tcp.closed);
        }
    }

    @Test public void teardownCannotHangWhenReceiverStopsReading() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect(); f.tcp.blockWrites = true;
            long start = System.nanoTime(); f.client.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100);
            await(() -> f.tcp.closed);
            assertEquals(0, f.failures.get());
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Condition did not complete", condition.getAsBoolean());
    }

    private static final class Fixture implements AutoCloseable {
        final LegacyMirrorSession session = new LegacyMirrorSession(123, 456, System.nanoTime());
        final FakeTcp tcp = new FakeTcp();
        final List<FakeUdp> udps = new CopyOnWriteArrayList<>();
        final AtomicInteger failures = new AtomicInteger(), bindings = new AtomicInteger(), tcpAllocations = new AtomicInteger();
        final RaopAudioClient client;
        volatile String message;
        Runnable afterUdpAllocation = () -> {};
        Fixture() { this(44100, 512, CONFIG); }
        Fixture(int sampleRate, int samplesPerFrame, byte[] config) {
            client = new RaopAudioClient(session, sampleRate, samplesPerFrame, config, value -> { message = value; failures.incrementAndGet(); }, socketFactory());
        }
        Fixture(VideoCodec videoCodec) {
            client = new RaopAudioClient(session, 44100, 512, CONFIG, value -> { message = value; failures.incrementAndGet(); }, videoCodec, socketFactory());
        }
        private RaopAudioClient.SocketFactory socketFactory() {
            return new RaopAudioClient.SocketFactory() {
                public Socket tcp() { tcpAllocations.incrementAndGet(); return tcp; }
                public DatagramSocket udp() throws IOException {
                    FakeUdp value = new FakeUdp(); udps.add(value); afterUdpAllocation.run(); return value;
                }
            };
        }
        LegacyMirrorClient.Binder binder() { return new LegacyMirrorClient.Binder() {
            public void bind(Socket socket) { bindings.incrementAndGet(); }
            public void bind(DatagramSocket socket) { bindings.incrementAndGet(); }
        }; }
        void connect() throws IOException { client.connect(PEER, 52244, binder()); }
        void connectNative(RaopAudioClient.NativeSetup setup, UnaryOperator<byte[]> encrypt) throws IOException {
            client.connectNative(PEER, binder(), setup, encrypt);
        }
        public void close() { client.close(); }
    }

    private static final class FakeTcp extends Socket {
        final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        final CountDownLatch writeEntered = new CountDownLatch(1);
        volatile boolean closed, blockWrites, changedSession;
        int rejectAt, rejectStatus = 401;
        String firstResponse, videoTransport, session = "DEADBEEF;timeout=60";
        String transport = "RTP/AVP/UDP;unicast;mode=record;server_port=6000;control_port=6001;timing_port=6002";
        public void connect(SocketAddress address, int timeout) {}
        public void setTcpNoDelay(boolean value) {}
        public void setSoTimeout(int value) {}
        public InetAddress getLocalAddress() { try { return InetAddress.getByName("192.168.71.117"); } catch (IOException error) { throw new AssertionError(error); } }
        public InputStream getInputStream() { return new InputStream() {
            public int read() throws IOException {
                if (closed) throw new SocketException("closed");
                try { Integer value = input.poll(20, TimeUnit.MILLISECONDS); if (value == null) throw new SocketTimeoutException(); return value; }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new SocketException("interrupted"); }
            }
        }; }
        public OutputStream getOutputStream() { return new OutputStream() {
            public void write(int value) throws IOException {
                writeEntered.countDown();
                while (blockWrites && !closed) {
                    try { Thread.sleep(5); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new SocketException("interrupted"); }
                }
                if (closed) throw new SocketException("closed");
                pending.write(value);
            }
            public void flush() {
                String request = pending.toString(StandardCharsets.US_ASCII);
                if (request.isEmpty()) return;
                pending.reset(); requests.add(request);
                if (requests.size() == 1 && firstResponse != null) { reply(firstResponse); return; }
                String seq = header(request, "CSeq");
                if (requests.size() == rejectAt) { reply("RTSP/1.0 " + rejectStatus + " Denied\r\nCSeq: " + seq + "\r\n\r\n"); return; }
                String extra = "";
                if (request.startsWith("SETUP ") && request.contains("/audio RTSP")) extra = "Session: " + session + "\r\nTransport: " + transport + "\r\n";
                if (request.startsWith("SETUP ") && request.contains("/video RTSP") && changedSession) extra = "Session: different\r\n";
                if (request.startsWith("SETUP ") && request.contains("/video RTSP") && videoTransport != null) extra += "Transport: " + videoTransport + "\r\n";
                if (request.startsWith("RECORD ")) extra = "Audio-Latency: 8820\r\n";
                reply("RTSP/1.0 200 OK\r\nCSeq: " + seq + "\r\n" + extra + "Content-Length: 0\r\n\r\n");
            }
        }; }
        private String header(String request, String name) {
            for (String line : request.split("\r\n")) if (line.startsWith(name + ": ")) return line.substring(name.length() + 2);
            return "";
        }
        void reply(String value) { for (byte b : value.getBytes(StandardCharsets.US_ASCII)) input.add(b & 255); }
        public synchronized void close() { closed = true; }
    }

    private static final class FakeUdp extends DatagramSocket {
        private record Incoming(byte[] bytes, InetAddress peer, int port) {}
        static final AtomicInteger NEXT_PORT = new AtomicInteger(40000);
        final int localPort = NEXT_PORT.incrementAndGet();
        final List<byte[]> sent = new CopyOnWriteArrayList<>();
        final BlockingQueue<Incoming> incoming = new LinkedBlockingQueue<>();
        volatile boolean closed, bound;
        int port;
        FakeUdp() throws SocketException { super((SocketAddress) null); }
        public void bind(SocketAddress address) { bound = true; }
        public void setSoTimeout(int value) {}
        public void connect(InetAddress address, int port) { this.port = port; }
        public int getPort() { return port; }
        public int getLocalPort() { return localPort; }
        public void send(DatagramPacket packet) throws IOException {
            if (closed) throw new SocketException("closed");
            sent.add(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
        }
        public void receive(DatagramPacket packet) throws IOException {
            if (closed) throw new SocketException("closed");
            try {
                Incoming value = incoming.poll(20, TimeUnit.MILLISECONDS);
                if (value == null) throw new SocketTimeoutException();
                int count = Math.min(packet.getLength(), value.bytes().length);
                System.arraycopy(value.bytes(), 0, packet.getData(), 0, count);
                packet.setLength(count); packet.setAddress(value.peer()); packet.setPort(value.port());
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new SocketException("interrupted"); }
        }
        void inject(byte[] bytes, InetAddress address, int port) { incoming.add(new Incoming(bytes, address, port)); }
        public void close() { closed = true; super.close(); }
    }
}
