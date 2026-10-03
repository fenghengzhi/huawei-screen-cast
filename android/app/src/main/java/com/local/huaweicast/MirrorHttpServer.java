package com.local.huaweicast;

import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Serves bounded live MPEG-TS streams and a rolling HLS playlist. */
public final class MirrorHttpServer extends NanoHTTPD {
    public final String basePath = "/live/" + UUID.randomUUID();
    private final CopyOnWriteArrayList<LiveInput> clients = new CopyOnWriteArrayList<>();
    private final LinkedHashMap<Integer, Segment> segments = new LinkedHashMap<>();
    private ByteArrayOutputStream current = new ByteArrayOutputStream();
    private long startPts = -1;
    private int sequence;
    private volatile boolean ended;
    private long mediaBytes;
    private volatile long lastRequest;
    public Runnable onJoin = () -> {};
    public Runnable onRead = () -> {};
    private record Segment(byte[] data, double duration) {}
    public MirrorHttpServer() { super(0); }
    public synchronized boolean emit(byte[] packet) {
        if (ended) return false;
        if (current.size() + packet.length <= 4 * 1024 * 1024) current.write(packet, 0, packet.length);
        boolean accepted = true;
        for (LiveInput client : clients) if (!client.queue.offer(packet)) { client.queue.clear(); accepted = false; }
        mediaBytes += packet.length;
        return accepted;
    }
    public synchronized void boundary(long pts) {
        if (ended) return;
        if (startPts >= 0 && current.size() > 0) {
            segments.put(sequence++, new Segment(current.toByteArray(), Math.max(.05, (pts - startPts) / 1000000.0)));
            while (segments.size() > 8) segments.remove(segments.keySet().iterator().next());
        }
        current = new ByteArrayOutputStream(); startPts = pts;
    }
    public synchronized int segmentCount() { return segments.size(); }
    public synchronized long bytes() { return mediaBytes; }
    public long lastRequest() { return lastRequest; }
    @Override public Response serve(IHTTPSession request) {
        String uri = request.getUri();
        if (!uri.startsWith(basePath + "/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        if (request.getMethod() != Method.GET && request.getMethod() != Method.HEAD) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET or HEAD required");
        if (ended) return unavailable();
        if (uri.equals(basePath + "/screen.ts")) {
            lastRequest = System.currentTimeMillis();
            if (request.getMethod() == Method.HEAD) return newFixedLengthResponse(Response.Status.OK, "video/mp2t", "");
            LiveInput input;
            synchronized (this) {
                if (ended) return unavailable();
                if (clients.size() >= 3) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Too many streams");
                input = new LiveInput(); clients.add(input);
            }
            try { onJoin.run(); }
            catch (RuntimeException | Error error) { input.close(); throw error; }
            Response response = newChunkedResponse(Response.Status.OK, "video/mp2t", input);
            response.addHeader("Cache-Control", "no-store"); response.addHeader("transferMode.dlna.org", "Streaming");
            return response;
        }
        if (uri.equals(basePath + "/screen.m3u8")) {
            StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n");
            synchronized (this) {
                if (segments.isEmpty()) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream warming up");
                List<Map.Entry<Integer, Segment>> window = new ArrayList<>(segments.entrySet());
                window = window.subList(Math.max(0, window.size() - 4), window.size());
                playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(window.get(0).getKey()).append('\n');
                for (Map.Entry<Integer, Segment> entry : window) playlist.append("#EXTINF:").append(String.format(Locale.US, "%.3f", entry.getValue().duration())).append(",\n").append(entry.getKey()).append(".ts\n");
            }
            lastRequest = System.currentTimeMillis();
            Response response = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", playlist.toString()); response.addHeader("Cache-Control", "no-store"); return response;
        }
        try {
            String filename = uri.substring(basePath.length() + 1);
            if (!filename.endsWith(".ts")) throw new IllegalArgumentException();
            int id = Integer.parseInt(filename.substring(0, filename.length() - 3));
            Segment segment;
            synchronized (this) { segment = segments.get(id); }
            if (segment == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Segment expired");
            lastRequest = System.currentTimeMillis(); onRead.run();
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp2t", new ByteArrayInputStream(segment.data()), segment.data().length);
            response.addHeader("Cache-Control", "no-store"); return response;
        } catch (IllegalArgumentException error) { return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found"); }
    }
    private static Response unavailable() {
        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream ended");
    }
    @Override public void stop() {
        synchronized (this) {
            ended = true;
            for (LiveInput client : clients) client.close();
            clients.clear(); segments.clear(); current.reset();
        }
        super.stop();
    }
    private final class LiveInput extends InputStream {
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(512);
        byte[] packet; int offset; volatile boolean closed;
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
        @Override public int read(byte[] buffer, int off, int len) throws IOException {
            Objects.requireNonNull(buffer, "buffer");
            if (off < 0 || len < 0 || off > buffer.length - len) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            int copied = 0;
            long deadline = 0;
            while (!closed && !ended) {
                if (packet == null || offset == packet.length) {
                    try {
                        if (copied == 0) packet = queue.poll(1, TimeUnit.SECONDS);
                        else {
                            packet = queue.poll();
                            if (packet == null) {
                                long remaining = deadline - System.nanoTime();
                                if (remaining <= 0) break;
                                packet = queue.poll(remaining, TimeUnit.NANOSECONDS);
                            }
                        }
                        offset = 0;
                    }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    if (packet == null) { if (copied > 0) break; continue; }
                }
                if (copied == 0) deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4);
                int n = Math.min(len - copied, packet.length - offset);
                System.arraycopy(packet, offset, buffer, off + copied, n); offset += n; copied += n;
                if (copied == len) break;
            }
            if (copied == 0) return -1;
            lastRequest = System.currentTimeMillis(); onRead.run(); return copied;
        }
        @Override public void close() { closed = true; clients.remove(this); queue.clear(); }
    }
}
