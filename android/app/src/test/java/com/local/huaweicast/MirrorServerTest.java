package com.local.huaweicast;
import org.junit.Test;
import static org.junit.Assert.*;
import fi.iki.elonen.NanoHTTPD;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MirrorServerTest {
    @Test public void liveReadsBatchPacketsAndPreservePartialReads() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer();
        try (NanoHTTPD.Response response = server.serve(session(server, "/screen.ts"))) {
            byte[] packet = new byte[188]; packet[0] = 0x47;
            for (int i=0; i<20; i++) server.emit(packet);
            byte[] buffer = new byte[4096];
            assertEquals(188 * 20, response.getData().read(buffer));
            for (int i=0; i<20; i++) assertEquals(0x47, buffer[i*188]);
            server.emit(packet);
            assertEquals(100, response.getData().read(buffer, 0, 100));
            assertEquals(88, response.getData().read(buffer));
        } finally { server.stop(); }
    }
    @Test public void hlsSegmentsHaveStableSequenceAndExpire() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer(); server.start(1000, true);
        String base = "http://127.0.0.1:" + server.getListeningPort() + server.basePath;
        byte[] packet = new byte[188]; packet[0] = 0x47;
        try {
            server.boundary(1000000);
            for (int i=0; i<12; i++) { server.emit(packet); server.boundary((i+2)*1000000L); }
            HttpURLConnection playlist = (HttpURLConnection) new URL(base + "/screen.m3u8").openConnection();
            assertEquals(200, playlist.getResponseCode()); String value = new String(playlist.getInputStream().readAllBytes(), StandardCharsets.UTF_8); playlist.disconnect();
            assertTrue(value.contains("#EXT-X-MEDIA-SEQUENCE:8")); assertTrue(value.contains("#EXTINF:1.000")); assertTrue(value.contains("11.ts")); assertFalse(value.contains("#EXT-X-ENDLIST"));
            HttpURLConnection segment = (HttpURLConnection) new URL(base + "/11.ts").openConnection(); assertArrayEquals(packet, segment.getInputStream().readAllBytes()); segment.disconnect();
            HttpURLConnection expired = (HttpURLConnection) new URL(base + "/0.ts").openConnection(); assertEquals(404, expired.getResponseCode()); expired.disconnect();
            HttpURLConnection secret = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getListeningPort() + "/live/wrong/screen.m3u8").openConnection(); assertEquals(404, secret.getResponseCode()); secret.disconnect();
        } finally { server.stop(); }
    }

    @Test(timeout = 10000) public void concurrentJoinsRespectTheClientLimit() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer();
        ExecutorService requests = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<NanoHTTPD.Response>> pending = new ArrayList<>();
        List<NanoHTTPD.Response> responses = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) pending.add(requests.submit(() -> {
                ready.countDown();
                if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("Request start timed out");
                return server.serve(session(server, "/screen.ts"));
            }));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            for (Future<NanoHTTPD.Response> request : pending) responses.add(request.get(2, TimeUnit.SECONDS));
            assertEquals(3, responses.stream().filter(response -> response.getStatus() == NanoHTTPD.Response.Status.OK).count());
            assertEquals(13, responses.stream().filter(response -> response.getStatus() == NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE).count());
        } finally {
            start.countDown();
            requests.shutdownNow();
            for (NanoHTTPD.Response response : responses) response.close();
            server.stop();
        }
    }

    @Test public void stoppingClosesExistingStreamsAndRejectsLaterRequests() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer();
        try (NanoHTTPD.Response live = server.serve(session(server, "/screen.ts"))) {
            server.boundary(0);
            server.emit(new byte[188]);
            server.boundary(1_000_000);
            assertEquals(1, server.segmentCount());
            server.stop();
            assertEquals(-1, live.getData().read());
            assertFalse(server.emit(new byte[188]));
            server.boundary(2_000_000);
            assertEquals(0, server.segmentCount());
            for (String path : new String[]{"/screen.ts", "/screen.m3u8", "/0.ts"}) {
                try (NanoHTTPD.Response response = server.serve(session(server, path))) {
                    assertEquals(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, response.getStatus());
                }
            }
        } finally { server.stop(); }
    }

    @Test(timeout = 5000) public void invalidReadArgumentsDoNotConsumeQueuedPackets() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer();
        try (NanoHTTPD.Response response = server.serve(session(server, "/screen.ts"))) {
            byte[] packet = new byte[]{1, 2, 3, 4};
            server.emit(packet);
            InputStream input = response.getData();
            assertThrows(NullPointerException.class, () -> input.read(null, 0, 0));
            byte[] buffer = new byte[4];
            for (int[] bounds : new int[][]{{-1, 1}, {0, -1}, {3, 2}, {5, 0}, {Integer.MAX_VALUE, 1}, {1, Integer.MAX_VALUE}}) {
                assertThrows(IndexOutOfBoundsException.class, () -> input.read(buffer, bounds[0], bounds[1]));
            }
            assertEquals(0, input.read(buffer, buffer.length, 0));
            assertEquals(packet.length, input.read(buffer));
            assertArrayEquals(packet, buffer);
        } finally { server.stop(); }
    }

    @Test public void failedJoinCallbacksReleaseTheirClientSlots() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer();
        List<NanoHTTPD.Response> responses = new ArrayList<>();
        try {
            server.onJoin = () -> { throw new IllegalStateException("Encoder is unavailable"); };
            for (int i = 0; i < 3; i++) assertThrows(IllegalStateException.class, () -> server.serve(session(server, "/screen.ts")));
            server.onJoin = () -> {};
            for (int i = 0; i < 3; i++) {
                NanoHTTPD.Response response = server.serve(session(server, "/screen.ts"));
                responses.add(response);
                assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());
            }
            try (NanoHTTPD.Response overflow = server.serve(session(server, "/screen.ts"))) {
                assertEquals(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, overflow.getStatus());
            }
        } finally {
            for (NanoHTTPD.Response response : responses) response.close();
            server.stop();
        }
    }

    private static NanoHTTPD.IHTTPSession session(MirrorHttpServer server, String path) {
        return (NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(MirrorServerTest.class.getClassLoader(), new Class[]{NanoHTTPD.IHTTPSession.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUri" -> server.basePath + path;
            case "getMethod" -> NanoHTTPD.Method.GET;
            case "getHeaders" -> java.util.Map.of();
            default -> null;
        });
    }
}
