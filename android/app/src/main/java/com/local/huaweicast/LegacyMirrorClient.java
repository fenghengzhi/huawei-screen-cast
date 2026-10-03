package com.local.huaweicast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Legacy AirPlay compatibility transport, not a proprietary Lelink implementation. */
public final class LegacyMirrorClient implements MirrorVideoTransport {
    public interface Binder {
        void bind(Socket socket) throws IOException;
        void bind(DatagramSocket socket) throws IOException;
    }
    interface SocketFactory {
        Socket createSocket() throws IOException;
        DatagramSocket createDatagramSocket() throws IOException;
    }
    private record CodecConfig(int width, int height, byte[] vps, byte[] sps, byte[] pps, long generation) {}
    private record Frame(byte[] data, long pts, boolean key, long queuedNs, CodecConfig config) {}
    private static final long MAX_QUEUE_AGE_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(8);
    private final Object frameLock = new Object();
    private final Set<Thread> ioThreads = ConcurrentHashMap.newKeySet();
    private final Consumer<String> failure;
    private final Runnable requestKey;
    private int width, height;
    private final SocketFactory socketFactory;
    private final LegacyMirrorSession sharedSession;
    private final int latencyMs;
    private final boolean pcCompatibilityProfile;
    private final VideoCodec videoCodec;
    private final AtomicBoolean closed = new AtomicBoolean(), connectionStarted = new AtomicBoolean();
    private boolean waitingKey = true;
    private CodecConfig configuration;
    private volatile long formatGeneration;
    private volatile Socket socket;
    private volatile DatagramSocket timing;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastWriteNs = System.nanoTime(), sentFrames, timingReplies;
    private long epochNs;
    public LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure) {
        this(width, height, requestKey, failure, null, 90);
    }
    public LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                              LegacyMirrorSession session, int latencyMs) {
        this(width, height, requestKey, failure, session, latencyMs, false);
    }
    public LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                              LegacyMirrorSession session, int latencyMs, boolean companionAudio) {
        this(width, height, requestKey, failure, session, latencyMs, companionAudio, VideoCodec.H264);
    }
    public LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                              LegacyMirrorSession session, int latencyMs, boolean companionAudio, VideoCodec videoCodec) {
        this(width, height, requestKey, failure, session, latencyMs, companionAudio, videoCodec, new SocketFactory() {
            @Override public Socket createSocket() { return new Socket(); }
            @Override public DatagramSocket createDatagramSocket() throws SocketException { return new DatagramSocket(null); }
        });
    }
    LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure, SocketFactory socketFactory) {
        this(width, height, requestKey, failure, null, 90, socketFactory);
    }
    LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                      LegacyMirrorSession session, int latencyMs, SocketFactory socketFactory) {
        this(width, height, requestKey, failure, session, latencyMs, false, socketFactory);
    }
    LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                      LegacyMirrorSession session, int latencyMs, boolean companionAudio, SocketFactory socketFactory) {
        this(width, height, requestKey, failure, session, latencyMs, companionAudio, VideoCodec.H264, socketFactory);
    }
    LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                      LegacyMirrorSession session, int latencyMs, boolean companionAudio, VideoCodec videoCodec, SocketFactory socketFactory) {
        validateDimensions(width, height);
        if (latencyMs < 0 || latencyMs > 5000) throw new IllegalArgumentException("Invalid mirror latency");
        if (companionAudio && session == null) throw new IllegalArgumentException("Missing shared audio session");
        this.width = width; this.height = height; this.requestKey = requestKey; this.failure = failure;
        this.socketFactory = socketFactory;
        this.sharedSession = session; this.latencyMs = latencyMs;
        this.videoCodec = java.util.Objects.requireNonNull(videoCodec);
        this.pcCompatibilityProfile = companionAudio || videoCodec == VideoCodec.H265;
    }
    public long sentFrames() { return sentFrames; }
    public long timingReplies() { return timingReplies; }
    public void configure(byte[] sps, byte[] pps) {
        if (videoCodec != VideoCodec.H264) throw new IllegalStateException("Expected HEVC parameter sets");
        synchronized (frameLock) {
            if (!closed.get()) configuration = new CodecConfig(width, height, null, sps.clone(), pps.clone(), formatGeneration);
        }
    }
    public void configureHevc(byte[] vps, byte[] sps, byte[] pps) {
        if (videoCodec != VideoCodec.H265) throw new IllegalStateException("Expected AVC parameter sets");
        // Validate the receiver's bounded legacy configuration before storing anything for the writer.
        LegacyMirrorWire.hevcConfiguration(vps, sps, pps);
        synchronized (frameLock) {
            if (!closed.get()) configuration = new CodecConfig(width, height, vps.clone(), sps.clone(), pps.clone(), formatGeneration);
        }
    }
    /** Call after stopping the old encoder and before starting its replacement. The connection and clock stay intact. */
    public void resize(int width, int height) {
        validateDimensions(width, height);
        synchronized (frameLock) {
            if (closed.get()) return;
            this.width = width; this.height = height; formatGeneration++;
            configuration = null; frames.clear(); waitingKey = true;
        }
    }
    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new IllegalArgumentException("Invalid mirror dimensions");
    }
    public void offer(byte[] data, long monotonicUs, boolean key) {
        boolean recover = false;
        synchronized (frameLock) {
            if (closed.get() || configuration == null) return;
            if (data.length > 2 * 1024 * 1024) recover = true;
            else if (waitingKey && !key) return;
            else {
                if (key) waitingKey = false;
                recover = !frames.offer(new Frame(data, monotonicUs, key, System.nanoTime(), configuration));
            }
            if (recover) { frames.clear(); waitingKey = true; }
        }
        if (recover) requestKey.run();
    }
    private void recoverQueue(long generation) {
        synchronized (frameLock) {
            if (closed.get() || generation != formatGeneration) return;
            frames.clear(); waitingKey = true;
        }
        requestKey.run();
    }
    public void connect(LelinkEndpoint endpoint, Binder binder) throws Exception {
        if (!connectionStarted.compareAndSet(false, true)) throw new IOException("兼容镜像连接已启动");
        try { connectOnce(endpoint, binder); }
        catch (Exception error) { close(); throw error; }
    }
    private void connectOnce(LelinkEndpoint endpoint, Binder binder) throws Exception {
        if (closed.get()) throw new IOException("兼容镜像已取消");
        if (endpoint.mirrorPort().isEmpty()) throw new IOException("未广播镜像端口");
        LegacyMirrorSession session = sharedSession == null ? LegacyMirrorSession.create() : sharedSession;
        epochNs = session.epochNs();
        DatagramSocket udp = socketFactory.createDatagramSocket(); timing = udp;
        if (closed.get()) { udp.close(); throw new IOException("兼容镜像已取消"); }
        binder.bind(udp); udp.bind(new InetSocketAddress(7010)); udp.setSoTimeout(1000);
        Socket tcp = socketFactory.createSocket(); socket = tcp;
        if (closed.get()) { tcp.close(); throw new IOException("兼容镜像已取消"); }
        binder.bind(tcp); tcp.connect(new InetSocketAddress(endpoint.address(), endpoint.mirrorPort().getAsInt()), 2500);
        tcp.setTcpNoDelay(true); tcp.setSoTimeout(1000);
        long id = session.deviceId();
        // In the PC compatibility profile GET establishes the session; G2's POST parser does not consume a body.
        byte[] info = pcCompatibilityProfile ? new byte[0] : LegacyMirrorWire.streamInfo(id, session.sessionId(), latencyMs);
        String host = endpoint.address().getHostAddress();
        if (host.contains(":")) host = "[" + host + "]";
        // This compatibility profile is explicitly plaintext; our own product name remains in the UA.
        String userAgent = pcCompatibilityProfile ? "AirParrot/1.1 HuaweiCast/1.0" : "HuaweiCast-Experimental/1.0";
        String header = "POST /stream HTTP/1.1\r\nHost: " + host + ":" + endpoint.mirrorPort().getAsInt()
            + "\r\nUser-Agent: " + userAgent + "\r\nX-Apple-Device-ID: 0x" + Long.toHexString(id)
            + (info.length == 0 ? "" : "\r\nContent-Type: application/x-apple-binary-plist")
            + "\r\nContent-Length: " + info.length + "\r\n\r\n";
        OutputStream out = tcp.getOutputStream();
        lastWriteNs = System.nanoTime();
        watchdog.scheduleWithFixedDelay(() -> {
            if (!closed.get() && System.nanoTime() - lastWriteNs > TimeUnit.SECONDS.toNanos(5)) fail("镜像连接写入超时");
        }, 1, 1, TimeUnit.SECONDS);
        // Use the same identity from the first HTTP request so receivers can associate it with RTSP.
        String preflight = "GET /stream.xml HTTP/1.1\r\nHost: " + host + ":" + endpoint.mirrorPort().getAsInt()
                + "\r\nUser-Agent: " + userAgent + "\r\nX-Apple-Device-ID: 0x" + Long.toHexString(id)
                + "\r\nX-Apple-ProtocolVersion: 0\r\nX-Apple-Client-Name: HuaweiCast"
                + "\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
        out.write(preflight.getBytes(StandardCharsets.US_ASCII)); out.flush();
        LelinkProbe.Response response = LelinkProbe.readResponse(tcp.getInputStream(), true, false);
        if (response.status() == 401 || response.status() == 403) throw new IOException("接收端要求授权，兼容镜像已停止");
        if (response.status() != 200) throw new IOException("接收端拒绝镜像能力请求 (" + response.status() + ")");
        LelinkProbe.parseCapabilities(response.body());
        if (closed.get()) throw new IOException("兼容镜像已取消");
        lastWriteNs = System.nanoTime();
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(header.getBytes(StandardCharsets.US_ASCII)); request.write(info);
        out.write(request.toByteArray()); out.flush();
        // This legacy receiver discards bytes coalesced with POST, including an immediately following frame.
        if (pcCompatibilityProfile) TimeUnit.MILLISECONDS.sleep(100);
        if (closed.get()) throw new IOException("兼容镜像已取消");
        start("legacy-mirror-timing", () -> timingLoop(udp, endpoint.address()));
        start("legacy-mirror-response", () -> responseLoop(tcp));
        start("legacy-mirror-writer", () -> {
            while (!closed.get()) {
                Frame frame = frames.poll(1, TimeUnit.SECONDS);
                if (frame == null) out.write(LegacyMirrorWire.heartbeatPacket());
                else {
                    CodecConfig config = frame.config();
                    if (config.generation() != formatGeneration) continue;
                    if (System.nanoTime() - frame.queuedNs() > MAX_QUEUE_AGE_NS) { recoverQueue(config.generation()); continue; }
                    long pts = Math.max(0, frame.pts() - epochNs / 1000);
                    if (videoCodec == VideoCodec.H265) {
                        if (frame.key()) out.write(LegacyMirrorWire.hevcCodecPacket(config.vps(), config.sps(), config.pps(), config.width(), config.height(), pts));
                        out.write(LegacyMirrorWire.hevcVideoPacket(frame.data(), config.width(), config.height(), pts));
                    } else {
                        if (frame.key()) out.write(LegacyMirrorWire.codecPacket(config.sps(), config.pps(), config.width(), config.height(), pts));
                        out.write(LegacyMirrorWire.videoPacket(frame.data(), config.width(), config.height(), pts));
                    }
                }
                out.flush(); lastWriteNs = System.nanoTime();
                if (frame != null) sentFrames++;
            }
        });
    }
    private void timingLoop(DatagramSocket udp, InetAddress peer) throws IOException {
        byte[] bytes = new byte[512];
        while (!closed.get()) {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            try { udp.receive(packet); } catch (SocketTimeoutException ignored) { continue; }
            if (!peer.equals(packet.getAddress()) || packet.getLength() != 48 || (bytes[0] & 7) != 3 || ((bytes[0] >>> 3) & 7) != 4) continue;
            long received = (System.nanoTime() - epochNs) / 1000;
            byte[] reply = LegacyMirrorWire.ntpReply(java.util.Arrays.copyOf(bytes, 48), received, (System.nanoTime() - epochNs) / 1000);
            udp.send(new DatagramPacket(reply, reply.length, peer, packet.getPort()));
            timingReplies++;
        }
    }
    private void responseLoop(Socket tcp) throws IOException {
        // Silence is allowed, but keep reading after a successful response to detect later disconnects or rejection.
        InputStream in = tcp.getInputStream();
        while (!closed.get()) {
            int first = readResponseByte(in, 0);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int[] remaining = {LelinkProbe.MAX_HEADERS};
            String status = readResponseLine(in, first, deadline, remaining);
            if (status.matches("HTTP/1\\.[01] (401|403)( .*)?")) throw new IOException("接收端要求授权，兼容镜像已停止");
            if (!status.matches("HTTP/1\\.[01] 2[0-9][0-9]( .*)?")) throw new IOException("接收端拒绝或不支持此镜像握手");
            Map<String, String> headers = new HashMap<>();
            while (true) {
                String line = readResponseLine(in, readResponseByte(in, deadline), deadline, remaining);
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon < 1 || Character.isWhitespace(line.charAt(0))) throw new IOException("接收端返回无效响应头");
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.put(name, line.substring(colon + 1).trim()) != null
                        && (name.equals("content-length") || name.equals("transfer-encoding"))) throw new IOException("接收端返回重复长度字段");
            }
            if (headers.containsKey("transfer-encoding")) throw new IOException("接收端返回不支持的镜像响应编码");
            String length = headers.getOrDefault("content-length", "0");
            if (!length.matches("[0-9]{1,8}")) throw new IOException("接收端返回无效响应长度");
            int count = Integer.parseInt(length);
            if (count > LelinkProbe.MAX_BODY) throw new IOException("接收端响应过大");
            for (int i = 0; i < count; i++) readResponseByte(in, deadline);
        }
    }
    private int readResponseByte(InputStream input, long deadline) throws IOException {
        while (!closed.get()) {
            if (deadline != 0 && System.nanoTime() - deadline >= 0) throw new SocketTimeoutException("接收端镜像响应超时");
            try {
                int value = input.read();
                if (value < 0) throw new EOFException("接收端关闭了镜像连接");
                return value;
            } catch (SocketTimeoutException timeout) {
                if (deadline != 0 && System.nanoTime() - deadline >= 0) throw new SocketTimeoutException("接收端镜像响应超时");
            }
        }
        throw new SocketException("镜像连接已关闭");
    }
    private String readResponseLine(InputStream input, int first, long deadline, int[] remaining) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value = first;
        while (true) {
            if (--remaining[0] < 0) throw new IOException("接收端响应头过大");
            if (value == '\r') {
                if (--remaining[0] < 0 || readResponseByte(input, deadline) != '\n') throw new IOException("接收端返回无效响应行");
                return line.toString(StandardCharsets.US_ASCII.name());
            }
            if (value < 32 || value > 126) throw new IOException("接收端返回无效响应字符");
            line.write(value);
            value = readResponseByte(input, deadline);
        }
    }
    private interface Action { void run() throws Exception; }
    private void start(String name, Action action) {
        Thread thread = new Thread(() -> {
            try { action.run(); }
            catch (Exception error) {
                if (!closed.get()) fail(error instanceof SocketException ? "接收端已断开或网络连接中断" : error.getMessage());
            }
            finally { ioThreads.remove(Thread.currentThread()); }
        }, name);
        thread.setDaemon(true); ioThreads.add(thread);
        if (closed.get()) { ioThreads.remove(thread); return; }
        thread.start();
    }
    private void fail(String message) { if (closeOnce()) failure.accept(message == null ? "兼容镜像连接失败" : message); }
    @Override public void close() { closeOnce(); }
    private boolean closeOnce() {
        if (!closed.compareAndSet(false, true)) return false;
        synchronized (frameLock) { frames.clear(); configuration = null; }
        watchdog.shutdownNow();
        Socket tcp = socket; if (tcp != null) try { tcp.close(); } catch (IOException ignored) {}
        DatagramSocket udp = timing; if (udp != null) udp.close();
        for (Thread thread : ioThreads) if (thread != Thread.currentThread()) thread.interrupt();
        return true;
    }
}
