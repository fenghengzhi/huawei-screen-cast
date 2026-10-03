package com.local.huaweicast;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Native type-1 FREE pairing and negotiated v2 media; never falls back to legacy or PIN auth. */
public final class LelinkMirrorClient implements MirrorVideoTransport {
    interface ControlSession extends AutoCloseable {
        LelinkControlClient.Response setup(byte[] plist) throws IOException;
        LelinkControlClient.Response teardown(byte[] plist) throws IOException;
        LelinkControlClient.Response keepalive() throws IOException;
        byte[] mediaSeed();
        @Override void close();
    }
    interface Connector {
        ControlSession connect(LelinkEndpoint endpoint, LelinkControlClient.SocketBinder binder) throws IOException;
    }
    interface SocketFactory {
        Socket createSocket() throws IOException;
        DatagramSocket createDatagramSocket() throws IOException;
    }
    private record CodecConfig(int width, int height, byte[] vps, byte[] sps, byte[] pps, long generation) {}
    private record Frame(byte[] bytes, long monotonicUs, boolean key, long queuedNs, CodecConfig config) {}

    private static final long MAX_QUEUE_AGE_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long WRITE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(4);
    private static final ScheduledExecutorService TEARDOWN_DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "LelinkTeardownDeadline");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(8);
    private final Object frameLock = new Object();
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean resourcesReleased = new AtomicBoolean();
    private final AtomicBoolean audioNegotiating = new AtomicBoolean();
    private final Runnable requestKey;
    private final Consumer<String> failure;
    private final LegacyMirrorSession clock;
    private final VideoCodec codec;
    private final Connector connector;
    private final SocketFactory socketFactory;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "LelinkMediaWatchdog");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ControlSession control;
    private volatile Socket controlSocket;
    private volatile Socket mediaSocket;
    private volatile DatagramSocket timingSocket;
    private volatile LelinkMediaWire.VideoEncryptor encryptor;
    private volatile boolean connected;
    private volatile boolean videoConfigured;
    private volatile boolean audioConfigured;
    private volatile long writeStartedNs;
    private volatile long sentFrames;
    private volatile long timingReplies;
    private volatile long generation;
    private int width;
    private int height;
    private int remoteTimingPort;
    private boolean waitingKey = true;
    private CodecConfig configuration;

    public LelinkMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                              LegacyMirrorSession clock, VideoCodec codec) {
        this(width, height, requestKey, failure, clock, codec, (endpoint, binder) -> {
            LelinkFreeControlSession session = LelinkFreeControlSession.connect(endpoint, binder);
            return new ControlSession() {
                public LelinkControlClient.Response setup(byte[] plist) throws IOException {
                    return session.control().setupStream(plist);
                }
                public LelinkControlClient.Response teardown(byte[] plist) throws IOException {
                    return session.control().teardownStream(plist);
                }
                public LelinkControlClient.Response keepalive() throws IOException {
                    return session.control().readPlayerInfo();
                }
                public byte[] mediaSeed() { return session.mediaSeed(); }
                public void close() { session.close(); }
            };
        }, new SocketFactory() {
            public Socket createSocket() { return new Socket(); }
            public DatagramSocket createDatagramSocket() throws SocketException { return new DatagramSocket(null); }
        });
    }

    LelinkMirrorClient(int width, int height, Runnable requestKey, Consumer<String> failure,
                       LegacyMirrorSession clock, VideoCodec codec, Connector connector, SocketFactory factory) {
        validateDimensions(width, height);
        this.width = width;
        this.height = height;
        this.requestKey = Objects.requireNonNull(requestKey);
        this.failure = Objects.requireNonNull(failure);
        this.clock = Objects.requireNonNull(clock);
        this.codec = Objects.requireNonNull(codec);
        this.connector = Objects.requireNonNull(connector);
        this.socketFactory = Objects.requireNonNull(factory);
    }

    @Override public void connect(LelinkEndpoint endpoint, LegacyMirrorClient.Binder binder) throws Exception {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(binder);
        if (!connecting.compareAndSet(false, true)) throw new IOException("Lelink connection has already started");
        try { connectOnce(endpoint, binder); }
        catch (Exception error) { closeOnce(false); throw error; }
    }

    private void connectOnce(LelinkEndpoint endpoint, LegacyMirrorClient.Binder binder) throws IOException {
        requireOpen();
        if (!endpoint.advertisesFreeNativePairing()) throw new IOException("Receiver does not offer FREE native pairing");
        DatagramSocket timing = socketFactory.createDatagramSocket();
        timingSocket = timing;
        if (closed.get()) { timing.close(); throw new IOException("Lelink connection was cancelled"); }
        binder.bind(timing);
        timing.bind(new InetSocketAddress(0));
        timing.setSoTimeout(1000);
        start("LelinkVideoTiming", () -> timingLoop(timing, endpoint.address()));
        ControlSession negotiated = connector.connect(endpoint, socket -> {
            controlSocket = socket;
            if (closed.get()) { closeSocket(socket); throw new IOException("Lelink connection was cancelled"); }
            binder.bind(socket);
        });
        control = negotiated;
        if (closed.get()) { negotiated.close(); throw new IOException("Lelink connection was cancelled"); }
        byte[] seed = negotiated.mediaSeed();
        try {
            synchronized (frameLock) {
                requireOpen();
                encryptor = new LelinkMediaWire.VideoEncryptor(seed, codec);
            }
        }
        finally { Arrays.fill(seed, (byte) 0); }
        requireOpen();
        LelinkStreamSetup.VideoPorts ports = LelinkStreamSetup.parseVideo(accepted("video SETUP",
                negotiated.setup(LelinkStreamSetup.videoRequest(timing.getLocalPort()))));
        videoConfigured = true;
        remoteTimingPort = ports.timingPort();
        requireOpen();
        Socket media = socketFactory.createSocket();
        mediaSocket = media;
        if (closed.get()) { closeSocket(media); throw new IOException("Lelink connection was cancelled"); }
        binder.bind(media);
        media.connect(new InetSocketAddress(endpoint.address(), ports.dataPort()), 3000);
        media.setTcpNoDelay(true);
        media.setSoTimeout(1000);
        requireOpen();
        OutputStream output = media.getOutputStream();
        connected = true;
        watchdog.scheduleWithFixedDelay(() -> {
            long started = writeStartedNs;
            if (!closed.get() && started != 0 && System.nanoTime() - started > WRITE_TIMEOUT_NS) {
                fail("Lelink media write timed out");
            }
        }, 1, 1, TimeUnit.SECONDS);
        start("LelinkVideoReader", () -> readUntilClosed(media));
        start("LelinkVideoWriter", () -> writerLoop(output));
        start("LelinkControlKeepalive", () -> {
            while (!closed.get()) {
                TimeUnit.SECONDS.sleep(10);
                if (closed.get()) return;
                LelinkPlayerInfo info = LelinkPlayerInfo.parse(accepted("keepalive", negotiated.keepalive()));
                if (!info.advertisesFreePairing()) throw new IOException("Receiver no longer offers FREE native pairing");
            }
        });
    }

    public RaopAudioClient.NativePorts setupAudio(int sampleRate, int localControlPort, int localTimingPort)
            throws IOException {
        try {
            if (!audioNegotiating.compareAndSet(false, true) || audioConfigured) {
                throw new IOException("Native audio setup has already started");
            }
            ControlSession session = requireConnected();
            LelinkStreamSetup.AudioPorts ports = LelinkStreamSetup.parseAudio(accepted("audio SETUP",
                    session.setup(LelinkStreamSetup.audioRequest(sampleRate, localControlPort, localTimingPort))));
            int timing = ports.timingPort() != 0 ? ports.timingPort() : remoteTimingPort;
            if (timing < 1 || timing > 65535) throw new IOException("Missing native audio timing port");
            requireOpen();
            audioConfigured = true;
            return new RaopAudioClient.NativePorts(ports.dataPort(), ports.controlPort(), timing);
        } catch (IOException | RuntimeException error) {
            closeOnce(false);
            throw error;
        } finally { audioNegotiating.set(false); }
    }

    /** The caller owns this copy and must erase it after initializing the audio cipher. */
    public byte[] mediaSeed() throws IOException { return requireConnected().mediaSeed(); }

    @Override public void configure(byte[] sps, byte[] pps) {
        if (codec != VideoCodec.H264) throw new IllegalStateException("Expected HEVC parameter sets");
        setConfiguration(null, sps, pps);
    }

    @Override public void configureHevc(byte[] vps, byte[] sps, byte[] pps) {
        if (codec != VideoCodec.H265) throw new IllegalStateException("Expected AVC parameter sets");
        setConfiguration(vps, sps, pps);
    }

    private void setConfiguration(byte[] vps, byte[] sps, byte[] pps) {
        synchronized (frameLock) {
            if (closed.get()) return;
            byte[] video = vps == null ? null : vps.clone();
            byte[] sequence = sps.clone(), picture = pps.clone();
            LelinkMediaWire.codecPacket(codec, video, sequence, picture, width, height, 0);
            configuration = new CodecConfig(width, height, video, sequence, picture, generation);
        }
    }

    @Override public void resize(int width, int height) {
        validateDimensions(width, height);
        synchronized (frameLock) {
            if (closed.get()) return;
            this.width = width;
            this.height = height;
            generation++;
            configuration = null;
            frames.clear();
            waitingKey = true;
        }
    }

    @Override public void offer(byte[] data, long monotonicUs, boolean key) {
        boolean recover = false;
        synchronized (frameLock) {
            if (closed.get() || configuration == null) return;
            if (data == null || data.length == 0 || data.length > LelinkMediaWire.MAX_PAYLOAD_SIZE) recover = true;
            else if (waitingKey && !key) return;
            else {
                recover = !frames.offer(new Frame(data.clone(), monotonicUs, key, System.nanoTime(), configuration));
                if (!recover && key) waitingKey = false;
            }
            if (recover) { frames.clear(); waitingKey = true; }
        }
        if (recover) requestKey.run();
    }

    @Override public long sentFrames() { return sentFrames; }
    public long timingReplies() { return timingReplies; }

    private void writerLoop(OutputStream output) throws Exception {
        while (!closed.get()) {
            Frame frame = frames.poll(1, TimeUnit.SECONDS);
            if (frame == null) continue;
            byte[] configurationPacket = null, videoPacket;
            boolean recover = false;
            synchronized (frameLock) {
                if (closed.get()) return;
                CodecConfig config = frame.config();
                if (config.generation() != generation) continue;
                // Discard stale frames before advancing the session's chained video cipher.
                if (System.nanoTime() - frame.queuedNs() > MAX_QUEUE_AGE_NS) {
                    frames.clear();
                    waitingKey = true;
                    recover = true;
                    videoPacket = null;
                } else {
                    long pts = clock.relativeUs(frame.monotonicUs());
                    if (frame.key()) configurationPacket = LelinkMediaWire.codecPacket(codec,
                            config.vps(), config.sps(), config.pps(), config.width(), config.height(), pts);
                    videoPacket = encryptor.videoPacket(frame.bytes(), frame.key(), config.width(), config.height(), pts);
                }
            }
            if (recover) { requestKey.run(); continue; }
            // Once encrypted, this frame must be sent in order or the entire connection closed.
            writeStartedNs = System.nanoTime();
            try {
                if (configurationPacket != null) output.write(configurationPacket);
                output.write(videoPacket);
                output.flush();
                sentFrames++;
            } finally { writeStartedNs = 0; }
        }
    }

    private void timingLoop(DatagramSocket socket, InetAddress peer) throws IOException {
        byte[] bytes = new byte[512];
        while (!closed.get()) {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            try { socket.receive(packet); } catch (SocketTimeoutException ignored) { continue; }
            if (!peer.equals(packet.getAddress())) continue;
            long receivedUs = clock.nowUs();
            byte[] response;
            if (packet.getLength() == 32) {
                byte[] request = Arrays.copyOf(bytes, 32);
                try {
                    RaopAudioWire.parseTimingRequest(request);
                    response = RaopAudioWire.timingReply(request, RaopAudioWire.ntpTimestampUs(receivedUs),
                            RaopAudioWire.ntpTimestampUs(clock.nowUs()));
                } catch (IllegalArgumentException ignored) { continue; }
            } else if (packet.getLength() == 48 && (bytes[0] & 7) == 3 && ((bytes[0] >>> 3) & 7) == 4) {
                response = LegacyMirrorWire.ntpReply(Arrays.copyOf(bytes, 48), receivedUs, clock.nowUs());
            } else continue;
            socket.send(new DatagramPacket(response, response.length, peer, packet.getPort()));
            timingReplies++;
        }
    }

    private void readUntilClosed(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        while (!closed.get()) {
            try {
                if (input.read() < 0) throw new EOFException("Receiver closed the native media connection");
                throw new IOException("Unexpected data on the native video connection");
            } catch (SocketTimeoutException ignored) { }
        }
    }

    private static byte[] accepted(String stage, LelinkControlClient.Response response) throws IOException {
        if (response.status() != 200) {
            throw new IOException("Receiver rejected native " + stage + " (status " + response.status() + ")");
        }
        return response.body();
    }

    private ControlSession requireConnected() throws IOException {
        requireOpen();
        ControlSession session = control;
        if (!connected || session == null) throw new IOException("Native media is not connected");
        return session;
    }

    private void requireOpen() throws IOException {
        if (closed.get()) throw new IOException("Lelink connection is closed");
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384) {
            throw new IllegalArgumentException("Invalid mirror dimensions");
        }
    }

    private interface Action { void run() throws Exception; }
    private void start(String name, Action action) {
        Thread thread = new Thread(() -> {
            try { action.run(); }
            catch (Exception error) { if (!closed.get()) fail(error.getMessage()); }
            finally { threads.remove(Thread.currentThread()); }
        }, name);
        thread.setDaemon(true);
        threads.add(thread);
        if (closed.get()) { threads.remove(thread); return; }
        thread.start();
    }

    private void fail(String message) {
        if (closeOnce(false)) failure.accept(message == null ? "Native Lelink connection failed" : message);
    }

    @Override public void close() { closeOnce(true); }

    private boolean closeOnce(boolean graceful) {
        if (!closed.compareAndSet(false, true)) return false;
        ControlSession session = control;
        boolean teardown = graceful && connected && videoConfigured && !audioNegotiating.get() && session != null;
        connected = false;
        watchdog.shutdownNow();
        for (Thread thread : threads) if (thread != Thread.currentThread()) thread.interrupt();
        if (!teardown) {
            releaseResources();
            return true;
        }
        // User stop must not wait for a busy encrypted request or an unresponsive peer.
        var deadline = TEARDOWN_DEADLINES.schedule(this::releaseResources, 500, TimeUnit.MILLISECONDS);
        Thread cleanup = new Thread(() -> {
            try {
                if (audioConfigured) accepted("audio TEARDOWN", session.teardown(LelinkStreamSetup.teardownRequest(96)));
                accepted("video TEARDOWN", session.teardown(LelinkStreamSetup.teardownRequest(97)));
            } catch (IOException | RuntimeException ignored) {
                // Best effort on this connection only; never re-pair or retry a rejected teardown.
            } finally {
                releaseResources();
                deadline.cancel(false);
            }
        }, "LelinkStreamTeardown");
        cleanup.setDaemon(true);
        cleanup.start();
        return true;
    }

    private void releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return;
        closeSocket(mediaSocket);
        closeSocket(controlSocket);
        DatagramSocket timing = timingSocket;
        if (timing != null) timing.close();
        ControlSession session = control;
        if (session != null) session.close();
        synchronized (frameLock) {
            frames.clear();
            configuration = null;
            LelinkMediaWire.VideoEncryptor cipher = encryptor;
            if (cipher != null) cipher.close();
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
