package com.local.huaweicast;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;

public class MediaServerTest {
    @Test public void serverStreamsOnlySelectedResourceAndHonorsRanges() throws Exception {
        Path file = Files.createTempFile("cast-test", ".mp4"); byte[] content = new byte[100];
        for (int i=0; i<content.length; i++) content[i] = (byte)i;
        Files.write(file, content); MediaServer server = new MediaServer(file.toFile(), "video/mp4"); server.start(1000, true);
        String base = "http://127.0.0.1:" + server.getListeningPort();
        try {
            HttpURLConnection full = (HttpURLConnection) new URL(base + server.mediaPath).openConnection();
            assertEquals(200, full.getResponseCode()); assertArrayEquals(content, full.getInputStream().readAllBytes()); full.disconnect();
            HttpURLConnection part = (HttpURLConnection) new URL(base + server.mediaPath).openConnection(); part.setRequestProperty("Range", "bytes=20-29");
            assertEquals(206, part.getResponseCode()); assertEquals("bytes 20-29/100", part.getHeaderField("Content-Range")); assertArrayEquals(Arrays.copyOfRange(content,20,30), part.getInputStream().readAllBytes()); part.disconnect();
            HttpURLConnection head = (HttpURLConnection) new URL(base + server.mediaPath).openConnection(); head.setRequestMethod("HEAD");
            assertEquals(200, head.getResponseCode()); assertEquals(100, head.getContentLength()); assertEquals(0, head.getInputStream().readAllBytes().length); head.disconnect();
            HttpURLConnection invalid = (HttpURLConnection) new URL(base + server.mediaPath).openConnection(); invalid.setRequestProperty("Range", "bytes=500-"); assertEquals(416, invalid.getResponseCode()); invalid.disconnect();
            HttpURLConnection hidden = (HttpURLConnection) new URL(base + "/media/wrong-token").openConnection(); assertEquals(404, hidden.getResponseCode()); hidden.disconnect();
        } finally { server.stop(); Files.deleteIfExists(file); }
    }
}
