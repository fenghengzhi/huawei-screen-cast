package com.local.huaweicast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** AAC-ELD UDP transport for plaintext legacy RAOP or an independently negotiated native session. */
public final class RaopAudioClient implements AutoCloseable {
    public static final class AuthorizationRequiredException extends IOException {
        public AuthorizationRequiredException() { super("接收端要求授权，声音连接已停止"); }
    }
    interface SocketFactory {
        Socket tcp() throws IOException;
        DatagramSocket udp() throws IOException;
    }
    public record NativePorts(int dataPort, int controlPort, int timingPort) {}
    @FunctionalInterface public interface NativeSetup {
        NativePorts negotiate(int localControlPort, int localTimingPort) throws IOException;
    }
    private record Frame(byte[] bytes, long ptsUs, long queuedNs, long index) {}
    private record CachedPacket(int sequence, byte[] bytes, long sentNs) {}
    private record Response(int status, Map<String, String> headers, byte[] body) {}
    private static final int MAX_FRAME_BYTES = 8192, MAX_HEADERS = 8192, MAX_BODY = 32768;
    private static final long REQUEST_NS = TimeUnit.SECONDS.toNanos(4);
    private static final long MAX_AGE_NS = TimeUnit.MILLISECONDS.toNanos(350);
    private final LegacyMirrorSession session;
    private final int sampleRate, samplesPerFrame;
    private final VideoCodec videoCodec;
    private final Consumer<String> failure;
    private final SocketFactory sockets;
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(32);
    private final Object offerLock = new Object();
    private final AtomicLong offeredFrames = new AtomicLong(), invalidFrames = new AtomicLong(), oversizedFrames = new AtomicLong();
    private final AtomicLong queueDrops = new AtomicLong(), staleDrops = new AtomicLong();
    private final CachedPacket[] history = new CachedPacket[128];
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final Map<Thread, Long> pendingWrites = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final ReentrantLock requestLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private final long ssrc, baseTimestamp;
    private int audioSequence, syncSequence, cseq;
    private volatile Socket tcp;
    private volatile DatagramSocket data, control, timing;
    private InetAddress peer;
    private int dataPort, controlPort, timingPort, mirrorPort;
    private volatile long requestStartedNs, sentFrames, timingReplies, resentFrames;
    private volatile long nonIncreasingPts, nearDuplicatePts;
    private volatile int latencySamples;
    private long keepaliveMs = 10000;
    private volatile boolean ready;
    private String uri, receiverSession;
    private UnaryOperator<byte[]> encryptPayload;
    private boolean nativeTransport;

    public RaopAudioClient(LegacyMirrorSession session, int sampleRate, int samplesPerFrame,
                           byte[] audioSpecificConfig, Consumer<String> failure) {
        this(session, sampleRate, samplesPerFrame, audioSpecificConfig, failure, VideoCodec.H264);
    }

    public RaopAudioClient(LegacyMirrorSession session, int sampleRate, int samplesPerFrame,
                           byte[] audioSpecificConfig, Consumer<String> failure, VideoCodec videoCodec) {
        this(session, sampleRate, samplesPerFrame, audioSpecificConfig, failure, videoCodec, new SocketFactory() {
            public Socket tcp() { return new Socket(); }
            public DatagramSocket udp() throws SocketException { return new DatagramSocket(null); }
        });
    }

    RaopAudioClient(LegacyMirrorSession session, int sampleRate, int samplesPerFrame,
                    byte[] audioSpecificConfig, Consumer<String> failure, SocketFactory sockets) {
        this(session, sampleRate, samplesPerFrame, audioSpecificConfig, failure, VideoCodec.H264, sockets);
    }

    RaopAudioClient(LegacyMirrorSession session, int sampleRate, int samplesPerFrame,
                    byte[] audioSpecificConfig, Consumer<String> failure, VideoCodec videoCodec, SocketFactory sockets) {
        this.videoCodec = Objects.requireNonNull(videoCodec, "Missing video codec");
        if (sampleRate < 8000 || sampleRate > 96000 || (samplesPerFrame != 480 && samplesPerFrame != 512))
            throw new IllegalArgumentException("Unsupported AAC-ELD audio format");
        AacEldConfig format = AacEldConfig.parse(audioSpecificConfig);
        if (format.sampleRate() != sampleRate || format.samplesPerFrame() != samplesPerFrame)
            throw new IllegalArgumentException("AAC-ELD clock does not match AudioSpecificConfig");
        this.session = Objects.requireNonNull(session);
        this.sampleRate = sampleRate; this.samplesPerFrame = samplesPerFrame;
        this.failure = Objects.requireNonNull(failure); this.sockets = sockets;
        SecureRandom random = new SecureRandom();
        ssrc = random.nextInt() & 0xffffffffL; baseTimestamp = random.nextInt() & 0xffffffffL;
        audioSequence = random.nextInt(65536); syncSequence = random.nextInt(65536);
        latencySamples = sampleRate / 10;
    }

    public long sentFrames() { return sentFrames; }
    public long timingReplies() { return timingReplies; }
    public long resentFrames() { return resentFrames; }
    public long droppedFrames() { return invalidFrames.get() + oversizedFrames.get() + queueDrops.get() + staleDrops.get(); }
    public long offeredFrames() { return offeredFrames.get(); }
    public long nonIncreasingPts() { return nonIncreasingPts; }
    public long nearDuplicatePts() { return nearDuplicatePts; }
    public String diagnostics() {
        return "offered=" + offeredFrames() + " sent=" + sentFrames + " drops[invalid=" + invalidFrames.get()
                + ",oversize=" + oversizedFrames.get() + ",queue=" + queueDrops.get() + ",stale=" + staleDrops.get()
                + "] pts[nonIncreasing=" + nonIncreasingPts + ",nearDuplicate=" + nearDuplicatePts + "]";
    }
    public OptionalInt negotiatedMirrorPort() { return mirrorPort == 0 ? OptionalInt.empty() : OptionalInt.of(mirrorPort); }
    public int latencyMs() { return (int) ((latencySamples * 1000L + sampleRate - 1) / sampleRate); }
    public String description() { return "AAC-ELD / " + sampleRate + " Hz / " + samplesPerFrame + " samples"; }

    /** Network activity starts here, after MediaProjection and playback capture consent. */
    public void connect(InetAddress peer, int advertisedPort, LegacyMirrorClient.Binder binder) throws IOException {
        if (!started.compareAndSet(false, true)) throw new IOException("声音连接已启动");
        try {
            validatePeer(peer);
            if (!validPort(advertisedPort)) throw new IOException("无效的本地声音接收端");
            this.peer = peer;
            ensureOpen();
            data = openUdp(binder); control = openUdp(binder); timing = openUdp(binder);
            Socket socket = sockets.tcp(); tcp = socket;
            ensureOpen(); binder.bind(socket);
            socket.connect(new InetSocketAddress(peer, advertisedPort), 2500);
            socket.setTcpNoDelay(true); socket.setSoTimeout(250);
            startWatchdog();
            String host = peer.getHostAddress();
            uri = "rtsp://" + (host.contains(":") ? "[" + host + "]" : host) + "/" + session.sessionId();
            request("ANNOUNCE", uri, Map.of("Content-Type", "application/sdp"), sdp(socket.getLocalAddress()));
            Response audioSetup = request("SETUP", uri + "/audio",
                    audioSetupHeaders(sampleRate, control.getLocalPort(), timing.getLocalPort()), null);
            receiverSession = parseSession(audioSetup.headers().get("session"));
            configureKeepalive(audioSetup.headers().get("session"));
            int[] ports = parseTransport(audioSetup.headers().get("transport"));
            dataPort = ports[0]; controlPort = ports[1]; timingPort = ports[2];
            data.connect(peer, dataPort); control.connect(peer, controlPort); timing.connect(peer, timingPort);
            // Timing packets can arrive while the receiver is processing RECORD.
            start("raop-timing", this::timingLoop);
            Response videoSetup = request("SETUP", uri + "/video", Map.of("Transport", "RTP/AVP/TCP;unicast;mode=record"), null);
            String videoTransport = videoSetup.headers().get("transport");
            if (videoTransport != null) {
                Map<String, String> parameters = parseTransportParameters(videoTransport, "RTP/AVP/TCP");
                if (parameters.containsKey("server_port")) {
                    mirrorPort = (int) decimal(parameters.get("server_port"), "server_port", 65535);
                    if (mirrorPort == 0) throw new IOException("接收端返回无效镜像端口");
                }
            }
            Response record = request("RECORD", uri, Map.of("Range", "npt=0-", "RTP-Info",
                    "seq=" + audioSequence + ";rtptime=" + baseTimestamp), null);
            String latency = record.headers().get("audio-latency");
            if (latency != null) {
                long value = decimal(latency, "Audio-Latency", sampleRate * 2L);
                latencySamples = (int) Math.max(sampleRate / 10, value);
            }
            ready = true;
            start("raop-audio", this::audioLoop);
            start("raop-resend", this::resendLoop);
            start("raop-keepalive", () -> {
                while (!closed.get()) {
                    Thread.sleep(keepaliveMs);
                    if (!closed.get()) request("OPTIONS", "*", Map.of(), null);
                }
            });
        } catch (IOException | RuntimeException error) {
            closeImmediately();
            // Cancellation can race with a socket being assigned after allocation.
            clearLocalResources(); closeSocket(tcp);
            throw error;
        }
    }

    /** The caller owns the native control session; this path never opens a legacy RTSP connection. */
    public void connectNative(InetAddress peer, LegacyMirrorClient.Binder binder, NativeSetup setup,
                              UnaryOperator<byte[]> encryptPayload) throws IOException {
        if (!started.compareAndSet(false, true)) throw new IOException("声音连接已启动");
        try {
            validatePeer(peer);
            Objects.requireNonNull(binder, "Missing socket binder");
            Objects.requireNonNull(setup, "Missing native audio setup");
            this.encryptPayload = Objects.requireNonNull(encryptPayload, "Missing audio encryptor");
            nativeTransport = true;
            this.peer = peer;
            ensureOpen();
            data = openUdp(binder); control = openUdp(binder); timing = openUdp(binder);
            ensureOpen();
            startWatchdog();
            NativePorts ports;
            requestStartedNs = System.nanoTime();
            try { ports = setup.negotiate(control.getLocalPort(), timing.getLocalPort()); }
            finally { requestStartedNs = 0; }
            ensureOpen();
            if (ports == null || !validPort(ports.dataPort()) || !validPort(ports.controlPort())
                    || !validPort(ports.timingPort())) throw new IOException("声音接收端返回无效端口");
            dataPort = ports.dataPort(); controlPort = ports.controlPort(); timingPort = ports.timingPort();
            data.connect(peer, dataPort); control.connect(peer, controlPort); timing.connect(peer, timingPort);
            ensureOpen();
            ready = true;
            start("raop-timing", this::timingLoop);
            start("raop-audio", this::audioLoop);
            start("raop-resend", this::resendLoop);
        } catch (IOException | RuntimeException error) {
            closeImmediately();
            // Cancellation can race with a socket being assigned after allocation.
            clearLocalResources();
            throw error;
        }
    }

    private void startWatchdog() {
        watchdog.scheduleWithFixedDelay(() -> {
            long since = requestStartedNs;
            if (since != 0 && System.nanoTime() - since >= REQUEST_NS && !closed.get()) fail("接收端声音握手或响应超时");
            for (long writeStart : pendingWrites.values()) {
                if (System.nanoTime() - writeStart >= REQUEST_NS && !closed.get()) fail("声音数据发送超时");
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private static boolean validPort(int port) { return port >= 1 && port <= 65535; }

    private static void validatePeer(InetAddress peer) throws IOException {
        if (peer == null || peer.isAnyLocalAddress() || peer.isLoopbackAddress() || peer.isMulticastAddress()
                || !(peer.isSiteLocalAddress() || peer.isLinkLocalAddress() || isUniqueLocal(peer)))
            throw new IOException("无效的本地声音接收端");
    }

    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static Map<String, String> audioSetupHeaders(int sampleRate, int controlPort, int timingPort) {
        if ((sampleRate != 44100 && sampleRate != 48000) || controlPort < 1 || controlPort > 65535
                || timingPort < 1 || timingPort > 65535) throw new IllegalArgumentException("Invalid audio SETUP parameters");
        return Map.of("Transport", "RTP/AVP/UDP;unicast;mode=screen;control_port=" + controlPort + ";timing_port=" + timingPort,
                "Audio-Type", "sample_rate=" + sampleRate + ";channels=2");
    }

    private DatagramSocket openUdp(LegacyMirrorClient.Binder binder) throws IOException {
        DatagramSocket socket = sockets.udp();
        try {
            ensureOpen(); binder.bind(socket); socket.bind(new InetSocketAddress(0)); socket.setSoTimeout(250);
            ensureOpen(); return socket;
        } catch (IOException | RuntimeException error) { socket.close(); throw error; }
    }

    public void offer(byte[] rawAac, long monotonicPtsUs) {
        synchronized (offerLock) {
            if (closed.get()) return;
            long index = offeredFrames.getAndIncrement();
            if (rawAac == null || rawAac.length == 0 || monotonicPtsUs < session.epochNs() / 1000) { invalidFrames.incrementAndGet(); return; }
            if (rawAac.length > MAX_FRAME_BYTES) { oversizedFrames.incrementAndGet(); return; }
            Frame frame = new Frame(rawAac.clone(), monotonicPtsUs, System.nanoTime(), index);
            if (!frames.offer(frame)) {
                // Keep a bounded live queue; retained indices preserve gaps from actual discarded audio.
                List<Frame> discarded = new ArrayList<>(32);
                queueDrops.addAndGet(frames.drainTo(discarded));
                if (!frames.offer(frame)) queueDrops.incrementAndGet();
            }
        }
    }

    private byte[] sdp(InetAddress local) {
        String address = local.getHostAddress();
        int scope = address.indexOf('%');
        if (scope >= 0) address = address.substring(0, scope);
        String family = local instanceof Inet6Address ? "IP6" : "IP4";
        // Legacy screen-mode SDP describes ELD with the rate, channel count and constantDuration.
        // Extra RFC 3640 config parameters are not part of the captured legacy ANNOUNCE format.
        String sdp = "v=0\r\no=AirTunes " + session.sessionId() + " 0 IN " + family + " " + address
                + "\r\ns=AirTunes\r\nc=IN " + family + " " + address + "\r\nt=0 0\r\n"
                + "m=audio 0 RTP/AVP 96\r\na=rtpmap:96 mpeg4-generic/" + sampleRate + "/2\r\n"
                + "a=fmtp:96 mode=AAC-eld; constantDuration=" + samplesPerFrame + "\r\n"
                + "a=min-latency:" + latencySamples + "\r\nm=video 0 RTP/AVP 97\r\na=rtpmap:97 "
                + (videoCodec == VideoCodec.H265 ? "H265" : "H264") + "\r\na=fmtp:97\r\n";
        return sdp.getBytes(StandardCharsets.US_ASCII);
    }

    private Response request(String method, String target, Map<String, String> headers, byte[] body) throws IOException {
        requestLock.lock();
        try {
            ensureOpen(); requestStartedNs = System.nanoTime();
            int sequence = ++cseq;
            OutputStream output = tcp.getOutputStream();
            output.write(requestHeader(method, target, headers, body == null ? 0 : body.length, sequence));
            if (body != null) output.write(body);
            output.flush();
            Response response = readResponse(tcp.getInputStream(), sequence, requestStartedNs + REQUEST_NS);
            if (response.status() == 401 || response.status() == 403) throw new AuthorizationRequiredException();
            if (response.status() != 200) throw new IOException("接收端拒绝声音请求 " + method + " (" + response.status() + ")");
            String token = response.headers().get("session");
            if (receiverSession != null && token != null && !receiverSession.equals(parseSession(token)))
                throw new IOException("接收端声音会话发生变化");
            return response;
        } finally { requestStartedNs = 0; requestLock.unlock(); }
    }

    private byte[] requestHeader(String method, String target, Map<String, String> headers, int bodyLength, int sequence) {
        StringBuilder head = new StringBuilder(method).append(' ').append(target).append(" RTSP/1.0\r\nCSeq: ")
                .append(sequence).append("\r\nUser-Agent: AirParrot/1.1 HuaweiCast/1.0\r\nX-LeLink-Device-ID: 0x")
                .append(Long.toHexString(session.deviceId())).append("\r\nX-Apple-Client-Name: HuaweiCast\r\n");
        if (receiverSession != null) head.append("Session: ").append(receiverSession).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        return head.append("Content-Length: ").append(bodyLength).append("\r\n\r\n").toString().getBytes(StandardCharsets.US_ASCII);
    }

    private Response readResponse(InputStream input, int sequence, long deadline) throws IOException {
        int[] remaining = {MAX_HEADERS};
        String status = readLine(input, deadline, remaining);
        if (!status.matches("RTSP/1\\.0 [0-9]{3}( .*)?")) throw new IOException("无效的声音 RTSP 响应");
        if (status.startsWith("RTSP/1.0 401") || status.startsWith("RTSP/1.0 403")) throw new AuthorizationRequiredException();
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readLine(input, deadline, remaining);
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[A-Za-z0-9-]+")) throw new IOException("无效的声音响应头");
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(name, line.substring(colon + 1).trim()) != null) throw new IOException("重复的声音响应头");
        }
        if (headers.containsKey("transfer-encoding")) throw new IOException("不支持的声音响应编码");
        if (decimal(headers.get("cseq"), "CSeq", Integer.MAX_VALUE) != sequence) throw new IOException("声音响应序号不匹配");
        int length = (int) decimal(headers.getOrDefault("content-length", "0"), "Content-Length", MAX_BODY);
        byte[] body = new byte[length];
        for (int i = 0; i < length; i++) body[i] = (byte) readByte(input, deadline);
        return new Response(Integer.parseInt(status.substring(9, 12)), headers, body);
    }

    private String readLine(InputStream input, long deadline, int[] remaining) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (--remaining[0] < 0) throw new IOException("声音响应头过大");
            int value = readByte(input, deadline);
            if (value == '\r') {
                if (--remaining[0] < 0 || readByte(input, deadline) != '\n') throw new IOException("无效的声音响应行");
                return line.toString(StandardCharsets.US_ASCII.name());
            }
            if (value < 32 || value > 126) throw new IOException("无效的声音响应字符");
            line.write(value);
        }
    }

    private int readByte(InputStream input, long deadline) throws IOException {
        while (true) {
            ensureOpen();
            if (System.nanoTime() - deadline >= 0) throw new SocketTimeoutException("声音响应超时");
            try {
                int value = input.read();
                if (value < 0) throw new EOFException("接收端关闭了声音连接");
                return value;
            } catch (SocketTimeoutException timeout) {
                if (System.nanoTime() - deadline >= 0) throw new SocketTimeoutException("声音响应超时");
            }
        }
    }

    private static long decimal(String text, String field, long max) throws IOException {
        if (text == null || !text.matches("[0-9]{1,10}")) throw new IOException("无效的声音响应字段 " + field);
        long value = Long.parseLong(text);
        if (value > max) throw new IOException("声音响应字段超出范围 " + field);
        return value;
    }

    private static String parseSession(String header) throws IOException {
        if (header == null || header.length() > 256) throw new IOException("声音接收端未返回有效 Session");
        String token = header.split(";", -1)[0].trim();
        if (!token.matches("[A-Za-z0-9._~+-]{1,128}")) throw new IOException("声音接收端返回无效 Session");
        return token;
    }

    private void configureKeepalive(String sessionHeader) throws IOException {
        boolean found = false;
        for (String field : sessionHeader.split(";")) {
            String[] item = field.trim().split("=", 2);
            if (item[0].equalsIgnoreCase("timeout")) {
                if (found || item.length != 2) throw new IOException("无效的声音会话超时参数");
                long seconds = decimal(item[1], "Session timeout", 86400);
                if (seconds == 0) throw new IOException("无效的声音会话超时参数");
                keepaliveMs = Math.max(500, Math.min(10000, seconds * 500)); found = true;
            }
        }
    }

    private int[] parseTransport(String header) throws IOException {
        if (header == null) throw new IOException("声音接收端未返回 UDP 端口");
        Map<String, String> values = parseTransportParameters(header, "RTP/AVP/UDP");
        int[] ports = new int[3]; String[] names = {"server_port", "control_port", "timing_port"};
        for (int i = 0; i < ports.length; i++) {
            ports[i] = (int) decimal(values.get(names[i]), names[i], 65535);
            if (ports[i] == 0) throw new IOException("声音接收端返回无效端口");
        }
        return ports;
    }

    private Map<String, String> parseTransportParameters(String header, String expectedProtocol) throws IOException {
        String[] fields = header.split(";", -1);
        if (!fields[0].trim().equalsIgnoreCase(expectedProtocol)) throw new IOException("接收端不支持请求的传输方式");
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < fields.length; i++) {
            String[] item = fields[i].trim().split("=", 2);
            String name = item[0].toLowerCase(Locale.ROOT);
            if (values.put(name, item.length == 1 ? "" : item[1]) != null) throw new IOException("重复的声音传输参数");
        }
        if (!values.containsKey("unicast") || values.containsKey("multicast")) throw new IOException("声音接收端未选择单播");
        // Ports may be negotiated, but the receiver must never redirect this stream to another address.
        if (values.containsKey("source") && !values.get("source").equals(peer.getHostAddress()))
            throw new IOException("声音接收端试图改变传输地址");
        return values;
    }

    private void audioLoop() throws Exception {
        long firstIndex = -1, lastPtsUs = -1, lastSyncNs = 0;
        boolean first = true;
        while (!closed.get()) {
            Frame frame = frames.poll(250, TimeUnit.MILLISECONDS);
            if (frame == null) continue;
            if (System.nanoTime() - frame.queuedNs() > MAX_AGE_NS) { staleDrops.incrementAndGet(); continue; }
            if (firstIndex < 0) firstIndex = frame.index();
            if (!first) {
                if (frame.ptsUs() <= lastPtsUs) nonIncreasingPts++;
                if (Math.abs(frame.ptsUs() - lastPtsUs) < samplesPerFrame * 500_000L / sampleRate) nearDuplicatePts++;
            }
            // One complete AAC access unit always advances its actual decoded sample count.
            // Hardware timestamp corrections must not turn valid audio into duplicate RTP frames.
            long timestamp = RaopAudioWire.timestampAfter(baseTimestamp, (frame.index() - firstIndex) * samplesPerFrame);
            long now = System.nanoTime();
            if (first || now - lastSyncNs >= TimeUnit.SECONDS.toNanos(1)) {
                byte[] sync = RaopAudioWire.syncPacket(syncSequence, (timestamp - latencySamples) & 0xffffffffL,
                        RaopAudioWire.ntpTimestampUs(session.relativeUs(frame.ptsUs())), timestamp, first);
                syncSequence = RaopAudioWire.nextSequence(syncSequence);
                send(control, sync, controlPort); lastSyncNs = now;
            }
            byte[] payload = encryptPayload == null ? frame.bytes() : encryptPayload.apply(frame.bytes());
            if (payload == null || payload.length == 0 || payload.length > MAX_FRAME_BYTES)
                throw new IOException("无效的声音加密数据");
            byte[] packet = RaopAudioWire.audioPacket(payload, audioSequence, timestamp, ssrc, first);
            send(data, packet, dataPort);
            synchronized (history) { history[audioSequence % history.length] = new CachedPacket(audioSequence, packet, now); }
            audioSequence = RaopAudioWire.nextSequence(audioSequence);
            first = false; lastPtsUs = frame.ptsUs(); sentFrames++;
        }
    }

    private void timingLoop() throws IOException {
        byte[] buffer = new byte[256];
        while (!closed.get()) {
            DatagramPacket packet = receive(timing, buffer);
            if (packet == null) continue;
            long receivedUs = session.nowUs();
            byte[] request = Arrays.copyOf(packet.getData(), packet.getLength());
            try {
                if (nativeTransport && request.length == 48) {
                    send(timing, LegacyMirrorWire.ntpReply(request, receivedUs, session.nowUs()), timingPort);
                } else {
                    RaopAudioWire.parseTimingRequest(request);
                    send(timing, RaopAudioWire.timingReply(request, RaopAudioWire.ntpTimestampUs(receivedUs),
                            RaopAudioWire.ntpTimestampUs(session.nowUs())), timingPort);
                }
                timingReplies++;
            } catch (IllegalArgumentException ignored) { /* Unrelated or malformed UDP data. */ }
        }
    }

    private void resendLoop() throws IOException {
        byte[] buffer = new byte[256];
        long windowNs = System.nanoTime(); int budget = 128;
        while (!closed.get()) {
            DatagramPacket packet = receive(control, buffer);
            if (packet == null) continue;
            long now = System.nanoTime();
            if (now - windowNs >= TimeUnit.SECONDS.toNanos(1)) { windowNs = now; budget = 128; }
            RaopAudioWire.ResendRequest request;
            try { request = RaopAudioWire.parseResendRequest(Arrays.copyOf(packet.getData(), packet.getLength()), 32); }
            catch (IllegalArgumentException ignored) { continue; }
            for (int i = 0; i < request.count() && budget > 0; i++) {
                int sequence = (request.firstSequence() + i) & 65535;
                CachedPacket cached;
                synchronized (history) { cached = history[sequence % history.length]; }
                if (cached == null || cached.sequence() != sequence || now - cached.sentNs() > TimeUnit.SECONDS.toNanos(1)) continue;
                send(control, RaopAudioWire.resendReply(request.sequence(), cached.bytes()), controlPort);
                budget--; resentFrames++;
            }
        }
    }

    private DatagramPacket receive(DatagramSocket socket, byte[] buffer) throws IOException {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try { socket.receive(packet); } catch (SocketTimeoutException ignored) { return null; }
        if (!peer.equals(packet.getAddress()) || packet.getPort() != socket.getPort()) return null;
        return packet;
    }

    private void send(DatagramSocket socket, byte[] bytes, int port) throws IOException {
        ensureOpen(); pendingWrites.put(Thread.currentThread(), System.nanoTime());
        try { socket.send(new DatagramPacket(bytes, bytes.length, peer, port)); }
        finally { pendingWrites.remove(Thread.currentThread()); }
    }

    private interface Action { void run() throws Exception; }
    private void start(String name, Action action) {
        Thread thread = new Thread(() -> {
            try { action.run(); }
            catch (Exception error) { if (!closed.get()) fail(error.getMessage()); }
            finally { threads.remove(Thread.currentThread()); }
        }, name);
        thread.setDaemon(true); threads.add(thread);
        if (closed.get()) { threads.remove(thread); return; }
        thread.start();
    }

    private void ensureOpen() throws SocketException { if (closed.get()) throw new SocketException("声音连接已关闭"); }
    private void fail(String message) {
        if (closeImmediately()) failure.accept(message == null ? "声音连接中断" : message);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        clearLocalResources();
        Socket socket = tcp;
        if (socket == null) { watchdog.shutdownNow(); return; }
        if (!ready || !requestLock.tryLock()) { closeSocket(socket); watchdog.shutdownNow(); return; }
        try {
            byte[] teardown = requestHeader("TEARDOWN", uri, Map.of(), 0, ++cseq);
            // Best effort, but a receiver that stops reading must never block app shutdown.
            watchdog.schedule(() -> closeSocket(socket), 500, TimeUnit.MILLISECONDS);
            Thread ending = new Thread(() -> {
                try { socket.getOutputStream().write(teardown); socket.getOutputStream().flush(); }
                catch (IOException ignored) {}
                finally { closeSocket(socket); watchdog.shutdownNow(); }
            }, "raop-teardown");
            ending.setDaemon(true); ending.start();
        } finally { requestLock.unlock(); }
    }

    private boolean closeImmediately() {
        if (!closed.compareAndSet(false, true)) return false;
        clearLocalResources(); closeSocket(tcp); watchdog.shutdownNow(); return true;
    }

    private void clearLocalResources() {
        synchronized (offerLock) { frames.clear(); }
        synchronized (history) { Arrays.fill(history, null); }
        for (DatagramSocket socket : new DatagramSocket[]{data, control, timing}) if (socket != null) socket.close();
        for (Thread thread : threads) if (thread != Thread.currentThread()) thread.interrupt();
    }

    private static void closeSocket(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) {} }
}
