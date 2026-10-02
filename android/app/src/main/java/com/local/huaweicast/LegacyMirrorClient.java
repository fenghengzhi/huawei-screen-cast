package com.local.huaweicast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Experimental legacy AirPlay transport, not a proprietary Lelink implementation. */
public final class LegacyMirrorClient implements AutoCloseable {
    public interface Binder {
        void bind(Socket socket) throws IOException;
        void bind(DatagramSocket socket) throws IOException;
    }
    interface SocketFactory {
        Socket createSocket() throws IOException;
        DatagramSocket createDatagramSocket() throws IOException;
    }
    private record Frame(byte[] data, long pts, boolean key) {}
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(8);
    private final Consumer<String> failure;
    private final Runnable requestKey;
    private final int width, height;
    private final SocketFactory socketFactory;
    private final AtomicBoolean closed = new AtomicBoolean(), connectionStarted = new AtomicBoolean();
    private volatile boolean waitingKey = true;
    private volatile byte[] sps, pps;
    private volatile Socket socket;
    private volatile DatagramSocket timing;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastWriteNs = System.nanoTime(), sentFrames, timingReplies;
    private long epochNs;
    public LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure) {
        this(width, height, requestKey, failure, new SocketFactory() {
            @Override public Socket createSocket() { return new Socket(); }
            @Override public DatagramSocket createDatagramSocket() throws SocketException { return new DatagramSocket(null); }
        });
    }
    LegacyMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure, SocketFactory socketFactory) {
        this.width = width; this.height = height; this.requestKey = requestKey; this.failure = failure;
        this.socketFactory = socketFactory;
    }
    public long sentFrames() { return sentFrames; }
    public long timingReplies() { return timingReplies; }
    public void configure(byte[] sps, byte[] pps) { this.sps = sps.clone(); this.pps = pps.clone(); }
    public void offer(byte[] data, long monotonicUs, boolean key) {
        if (closed.get()) return;
        if (data.length > 2 * 1024 * 1024) { frames.clear(); waitingKey = true; requestKey.run(); return; }
        if (waitingKey && !key) return;
        if (key) waitingKey = false;
        if (!frames.offer(new Frame(data, monotonicUs, key))) {
            frames.clear(); waitingKey = true; requestKey.run();
        }
    }
    public void connect(LelinkEndpoint endpoint, Binder binder) throws Exception {
        if (!connectionStarted.compareAndSet(false, true)) throw new IOException("实验连接已启动");
        try { connectOnce(endpoint, binder); }
        catch (Exception error) { close(); throw error; }
    }
    private void connectOnce(LelinkEndpoint endpoint, Binder binder) throws Exception {
        if (closed.get()) throw new IOException("实验已取消");
        if (endpoint.mirrorPort().isEmpty()) throw new IOException("未广播镜像端口");
        epochNs = System.nanoTime();
        DatagramSocket udp = socketFactory.createDatagramSocket(); timing = udp;
        if (closed.get()) { udp.close(); throw new IOException("实验已取消"); }
        binder.bind(udp); udp.bind(new InetSocketAddress(7010)); udp.setSoTimeout(1000);
        Socket tcp = socketFactory.createSocket(); socket = tcp;
        if (closed.get()) { tcp.close(); throw new IOException("实验已取消"); }
        binder.bind(tcp); tcp.connect(new InetSocketAddress(endpoint.address(), endpoint.mirrorPort().getAsInt()), 2500);
        tcp.setTcpNoDelay(true); tcp.setSoTimeout(1000);
        SecureRandom random = new SecureRandom();
        long id = (random.nextLong() & 0x0000ffffffffffffL) | 0x020000000000L;
        byte[] info = LegacyMirrorWire.streamInfo(id, random.nextInt() & 0x7fffffffL, 90);
        String host = endpoint.address().getHostAddress();
        if (host.contains(":")) host = "[" + host + "]";
        String header = "POST /stream HTTP/1.1\r\nHost: " + host + ":" + endpoint.mirrorPort().getAsInt()
            + "\r\nUser-Agent: HuaweiCast-Experimental/1.0\r\nX-Apple-Device-ID: 0x" + Long.toHexString(id)
            + "\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: " + info.length + "\r\n\r\n";
        OutputStream out = tcp.getOutputStream();
        lastWriteNs = System.nanoTime();
        watchdog.scheduleAtFixedRate(() -> {
            if (!closed.get() && System.nanoTime() - lastWriteNs > TimeUnit.SECONDS.toNanos(5)) fail("镜像连接写入超时");
        }, 1, 1, TimeUnit.SECONDS);
        // Some receivers attach the mirror session to the capability request's TCP connection.
        String preflight = "GET /stream.xml HTTP/1.1\r\nHost: " + host + ":" + endpoint.mirrorPort().getAsInt()
                + "\r\nUser-Agent: HuaweiCast-Experimental/1.0\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n";
        out.write(preflight.getBytes(StandardCharsets.US_ASCII)); out.flush();
        LelinkProbe.Response response = LelinkProbe.readResponse(tcp.getInputStream(), true, false);
        if (response.status() == 401 || response.status() == 403) throw new IOException("接收端要求授权，实验已停止");
        if (response.status() != 200) throw new IOException("接收端拒绝镜像能力请求 (" + response.status() + ")");
        LelinkProbe.parseCapabilities(response.body());
        if (closed.get()) throw new IOException("实验已取消");
        lastWriteNs = System.nanoTime();
        out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(info); out.flush();
        start("legacy-mirror-timing", () -> timingLoop(udp, endpoint.address()));
        start("legacy-mirror-response", () -> responseLoop(tcp));
        start("legacy-mirror-writer", () -> {
            boolean configured = false;
            while (!closed.get()) {
                Frame frame = frames.poll(1, TimeUnit.SECONDS);
                if (frame == null) out.write(LegacyMirrorWire.heartbeatPacket());
                else {
                    byte[] s = sps, p = pps;
                    if (s == null || p == null || (!configured && !frame.key())) continue;
                    long pts = Math.max(0, frame.pts() - epochNs / 1000);
                    if (frame.key()) { out.write(LegacyMirrorWire.codecPacket(s, p, width, height, pts)); configured = true; }
                    out.write(LegacyMirrorWire.videoPacket(frame.data(), width, height, pts));
                    sentFrames++;
                }
                out.flush(); lastWriteNs = System.nanoTime();
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
        // Legacy mirroring need not reply with HTTP. Any explicit rejection terminates the attempt.
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        InputStream in = tcp.getInputStream();
        while (!closed.get()) {
            int value;
            try { value = in.read(); } catch (SocketTimeoutException ignored) { continue; }
            if (value < 0) throw new EOFException("接收端关闭了实验连接");
            header.write(value);
            if (header.size() > 8192) throw new IOException("接收端返回无法识别的数据");
            if (value == '\n') {
                String line = header.toString(StandardCharsets.US_ASCII.name()).trim();
                if (line.matches("HTTP/1\\.[01] (401|403)( .*)?")) throw new IOException("接收端要求授权，实验已停止");
                if (!line.matches("HTTP/1\\.[01] 2[0-9][0-9]( .*)?")) throw new IOException("接收端拒绝或不支持此镜像握手");
                return;
            }
        }
    }
    private interface Action { void run() throws Exception; }
    private void start(String name, Action action) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Exception error) { if (!closed.get()) fail(error.getMessage()); } }, name);
        thread.setDaemon(true); thread.start();
    }
    private void fail(String message) { if (closeOnce()) failure.accept(message == null ? "实验镜像连接失败" : message); }
    @Override public void close() { closeOnce(); }
    private boolean closeOnce() {
        if (!closed.compareAndSet(false, true)) return false;
        frames.clear(); watchdog.shutdownNow();
        Socket tcp = socket; if (tcp != null) try { tcp.close(); } catch (IOException ignored) {}
        DatagramSocket udp = timing; if (udp != null) udp.close();
        return true;
    }
}
