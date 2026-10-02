package com.local.huaweicast;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MirrorServerTest {
    @Test public void liveReadsBatchPacketsAndPreservePartialReads() throws Exception {
        MirrorHttpServer server = new MirrorHttpServer();
        fi.iki.elonen.NanoHTTPD.IHTTPSession session = (fi.iki.elonen.NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{fi.iki.elonen.NanoHTTPD.IHTTPSession.class}, (p,m,a) -> switch(m.getName()) {
            case "getUri" -> server.basePath + "/screen.ts";
            case "getMethod" -> fi.iki.elonen.NanoHTTPD.Method.GET;
            case "getHeaders" -> java.util.Map.of();
            default -> null;
        });
        try (fi.iki.elonen.NanoHTTPD.Response response = server.serve(session)) {
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
}
