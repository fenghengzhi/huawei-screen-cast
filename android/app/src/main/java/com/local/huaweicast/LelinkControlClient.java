package com.local.huaweicast;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded native Lelink control transport, not an authenticated mirror implementation. */
public final class LelinkControlClient implements AutoCloseable {
    static final int MAX_HEADERS = 8192;
    static final int MAX_BODY = 32768;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int REQUEST_TIMEOUT_MS = 8000;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "LelinkControlDeadline");
        thread.setDaemon(true);
        return thread;
    });

    public interface SocketBinder { void bind(Socket socket) throws IOException; }
    interface SocketFactory { Socket create() throws IOException; }

    public record Response(int status, Map<String, String> headers, byte[] body) {
        public Response {
            headers = Map.copyOf(headers);
            body = body.clone();
        }
        @Override public byte[] body() { return body.clone(); }
    }

    private final Socket socket;
    private final String host;
    private final int requestTimeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String sessionId = UUID.randomUUID().toString();
    private final String clientUid = UUID.randomUUID().toString();
    private final Object cryptoLock = new Object();
    private volatile LelinkSecureRecord.Encoder encoder;
    private volatile LelinkSecureRecord.Decoder decoder;
    private InputStream responseInput;

    private LelinkControlClient(Socket socket, String host, int requestTimeoutMs) throws IOException {
        this.socket = socket;
        this.host = host;
        this.requestTimeoutMs = requestTimeoutMs;
        responseInput = socket.getInputStream();
    }

    /** Uses the discovered local address and control port, never a redirect or guessed port. */
    public static LelinkControlClient connect(LelinkEndpoint endpoint, SocketBinder binder) throws IOException {
        return connect(endpoint, binder, REQUEST_TIMEOUT_MS);
    }

    static LelinkControlClient connect(LelinkEndpoint endpoint, SocketBinder binder, int requestTimeoutMs)
            throws IOException {
        return connect(endpoint, binder, requestTimeoutMs, Socket::new);
    }

    static LelinkControlClient connect(LelinkEndpoint endpoint, SocketBinder binder, int requestTimeoutMs,
                                      SocketFactory factory) throws IOException {
        if (endpoint == null || binder == null) throw new IllegalArgumentException("Receiver and socket binder are required");
        if (requestTimeoutMs < 1 || requestTimeoutMs > REQUEST_TIMEOUT_MS) {
            throw new IllegalArgumentException("Invalid request timeout");
        }
        Socket socket = factory.create();
        try {
            binder.bind(socket);
            socket.connect(new InetSocketAddress(endpoint.address(), endpoint.controlPort()), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(requestTimeoutMs);
            String address = endpoint.address().getHostAddress();
            String host = (endpoint.address() instanceof Inet6Address ? "[" + address + "]" : address)
                    + ":" + endpoint.controlPort();
            return new LelinkControlClient(socket, host, requestTimeoutMs);
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    /** Sends caller-generated setup bytes. No credentials, keys, or successful auth are assumed. */
    public Response setup(byte[] body) throws IOException {
        return request("POST", "/lelink-setup", payload(body));
    }

    /** Sends caller-generated verification bytes on the same connection as setup. */
    public Response verify(byte[] body) throws IOException {
        return request("POST", "/lelink-verify", payload(body));
    }

    /**
     * Explicit only: this endpoint returns an AirPlay-style plist and may notify receiver
     * session state. It is not side-effect-free discovery or proof of native mirror support.
     */
    public Response readServerInfo() throws IOException {
        return request("GET", "/server-info", new byte[0]);
    }

    /** Native player metadata handshake; explicit only and does not itself start media. */
    public Response readPlayerInfo() throws IOException {
        return request("GET", "/lelink-player-info", new byte[0]);
    }

    /**
     * Starts native stream negotiation. Call only after screen-capture consent and successful
     * pairing; this can open the receiver's player. The caller owns the binary plist schema.
     */
    public synchronized Response setupStream(byte[] plist) throws IOException {
        if (encoder == null) throw new IllegalStateException("Native stream setup requires encryption");
        return request("SETUP", "/", binaryPlist(plist), "application/plist-binary");
    }

    /** Ends negotiated streams on the existing encrypted control connection. */
    public synchronized Response teardownStream(byte[] plist) throws IOException {
        if (encoder == null) throw new IllegalStateException("Native stream teardown requires encryption");
        return request("TEARDOWN", "/", binaryPlist(plist), "application/plist-binary");
    }

    /** Switch only after authenticating the plaintext M6 response. No plaintext fallback exists. */
    public synchronized void enableEncryption(byte[] key, byte[] nonce) throws IOException {
        if (key == null || key.length != 32 || nonce == null || nonce.length != 32) {
            throw new IllegalArgumentException("Expected 32-byte control key and nonce");
        }
        synchronized (cryptoLock) {
            if (closed.get()) throw new IOException("Lelink control connection is closed");
            if (encoder != null) throw new IllegalStateException("Control encryption is already enabled");
            byte[] shortNonce = Arrays.copyOf(nonce, 8);
            try {
                encoder = new LelinkSecureRecord.Encoder(key, shortNonce);
                decoder = new LelinkSecureRecord.Decoder(key, shortNonce);
                responseInput = new RecordInput(responseInput, decoder);
            } catch (RuntimeException error) {
                close();
                throw error;
            } finally {
                Arrays.fill(shortNonce, (byte) 0);
            }
        }
    }

    private static byte[] payload(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY) {
            throw new IllegalArgumentException("Invalid control payload length");
        }
        return body.clone();
    }

    private static byte[] binaryPlist(byte[] plist) {
        byte[] body = payload(plist);
        byte[] signature = "bplist00".getBytes(StandardCharsets.US_ASCII);
        if (body.length < signature.length || !Arrays.equals(signature, Arrays.copyOf(body, signature.length))) {
            throw new IllegalArgumentException("Native stream request requires a binary plist");
        }
        return body;
    }

    private synchronized Response request(String method, String path, byte[] body) throws IOException {
        return request(method, path, body, method.equals("POST") ? "application/octet-stream" : null);
    }

    private synchronized Response request(String method, String path, byte[] body, String contentType) throws IOException {
        if (closed.get()) throw new IOException("Lelink control connection is closed");
        String header = method + " " + path + " HTTP/1.1\r\nHost: " + host
                + "\r\nUser-Agent: HuaweiCast/1.0\r\nConnection: keep-alive\r\n"
                + "LeLink-Platform: Android\r\nLeLink-Session-ID: " + sessionId
                + "\r\nLeLink-Client-UID: " + clientUid + "\r\n"
                + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
                + "Content-Length: " + body.length + "\r\n\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        LelinkSecureRecord.Encoder currentEncoder = encoder;
        // This transport emits one record per request; invalid sizes must not advance its cipher.
        if (currentEncoder != null && headerBytes.length + body.length > LelinkSecureRecord.MAX_PLAINTEXT_SIZE) {
            throw new IllegalArgumentException("Encrypted HTTP request exceeds the control record limit");
        }
        byte[] message = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, message, 0, headerBytes.length);
        System.arraycopy(body, 0, message, headerBytes.length, body.length);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean expired = new AtomicBoolean();
        var timeout = DEADLINES.schedule(() -> {
            if (active.compareAndSet(true, false)) {
                expired.set(true);
                close();
            }
        }, requestTimeoutMs, TimeUnit.MILLISECONDS);
        try {
            if (currentEncoder != null) {
                byte[] plaintext = message;
                try { message = currentEncoder.encode(plaintext); }
                finally { Arrays.fill(plaintext, (byte) 0); }
            }
            socket.getOutputStream().write(message);
            socket.getOutputStream().flush();
            Parsed parsed = readResponse(responseInput);
            if (!active.compareAndSet(true, false)) throw new SocketTimeoutException("Lelink control deadline reached");
            Response response = parsed.response();
            if (parsed.closeConnection() || response.status() == 401 || response.status() == 403) close();
            return response;
        } catch (IOException | RuntimeException error) {
            close();
            if (expired.get()) {
                SocketTimeoutException timeoutError = new SocketTimeoutException("Lelink control deadline reached");
                timeoutError.initCause(error);
                throw timeoutError;
            }
            throw error;
        } finally {
            active.set(false);
            timeout.cancel(false);
        }
    }

    private record Parsed(Response response, boolean closeConnection) {}

    private static Parsed readResponse(InputStream input) throws IOException {
        int headerBudget = MAX_HEADERS;
        for (int interim = 0; interim <= 4; interim++) {
            byte[] raw = readHeaders(input, headerBudget);
            headerBudget -= raw.length;
            String[] lines = new String(raw, StandardCharsets.US_ASCII).split("\r\n");
            if (lines.length == 0) throw new IOException("Missing HTTP status");
            String[] status = lines[0].split(" ", 3);
            if (status.length < 2 || !(status[0].equals("HTTP/1.1") || status[0].equals("HTTP/1.0"))
                    || !status[1].matches("[1-9][0-9]{2}")) throw new IOException("Invalid HTTP status");
            int code = Integer.parseInt(status[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon < 1 || !lines[i].substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                    throw new IOException("Invalid HTTP header");
                }
                String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if (headers.putIfAbsent(key, value) != null) {
                    if (key.equals("content-length") || key.equals("transfer-encoding")) {
                        throw new IOException("Ambiguous HTTP framing");
                    }
                    headers.put(key, headers.get(key) + ", " + value);
                }
            }
            String length = headers.get("content-length");
            String transfer = headers.get("transfer-encoding");
            if (transfer != null && (length != null || !transfer.equalsIgnoreCase("chunked"))) {
                throw new IOException("Unsupported HTTP framing");
            }
            int size = length == null ? -1 : parseLength(length, MAX_BODY, 10);
            if (code < 200) {
                if (code == 101 || transfer != null || size > 0) throw new IOException("Unsupported informational response");
                continue;
            }
            boolean connectionClose = status[0].equals("HTTP/1.0")
                    || Arrays.stream(headers.getOrDefault("connection", "").split(","))
                    .anyMatch(token -> token.trim().equalsIgnoreCase("close"));
            byte[] body;
            if (code == 204 || code == 304) {
                if (transfer != null || size > 0) throw new IOException("Unexpected HTTP body");
                body = new byte[0];
            } else if (transfer != null) {
                body = readChunks(input, headerBudget);
            } else if (size >= 0) {
                body = readExactly(input, size);
            } else {
                body = readToEnd(input);
                connectionClose = true;
            }
            return new Parsed(new Response(code, headers, body), connectionClose);
        }
        throw new IOException("Too many informational responses");
    }

    private static byte[] readHeaders(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] delimiter = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < delimiter.length) {
            if (bytes.size() >= limit) throw new IOException("HTTP headers too large");
            int value = input.read();
            if (value < 0) throw new EOFException("Truncated HTTP headers");
            if (value > 126 || (value < 32 && value != '\r' && value != '\n' && value != '\t')) {
                throw new IOException("Invalid HTTP header character");
            }
            bytes.write(value);
            matched = value == delimiter[matched] ? matched + 1 : value == '\r' ? 1 : 0;
        }
        String text = bytes.toString(StandardCharsets.US_ASCII.name());
        String noLines = text.replace("\r\n", "");
        if (noLines.indexOf('\r') >= 0 || noLines.indexOf('\n') >= 0 || text.charAt(0) == '\t') {
            throw new IOException("Invalid HTTP header lines");
        }
        return bytes.toByteArray();
    }

    private static byte[] readChunks(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int framing = 0;
        while (true) {
            String line = readLine(input, limit - framing);
            framing += line.length() + 2;
            int extension = line.indexOf(';');
            int size = parseLength(extension < 0 ? line : line.substring(0, extension), MAX_BODY - body.size(), 16);
            if (size == 0) {
                while (true) {
                    line = readLine(input, limit - framing);
                    framing += line.length() + 2;
                    if (line.isEmpty()) return body.toByteArray();
                    int colon = line.indexOf(':');
                    if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                            || line.substring(0, colon).equalsIgnoreCase("content-length")
                            || line.substring(0, colon).equalsIgnoreCase("transfer-encoding")) {
                        throw new IOException("Invalid HTTP trailer");
                    }
                }
            }
            body.write(readExactly(input, size));
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid chunk ending");
            framing += 2;
            if (framing > limit) throw new IOException("HTTP chunk framing too large");
        }
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            if (bytes.size() + 2 > limit) throw new IOException("HTTP chunk framing too large");
            int value = input.read();
            if (value < 0) throw new EOFException("Truncated HTTP chunk framing");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid HTTP chunk framing");
                return bytes.toString(StandardCharsets.US_ASCII.name());
            }
            if (value < 32 || value > 126) throw new IOException("Invalid HTTP chunk character");
            bytes.write(value);
        }
    }

    private static int parseLength(String value, int limit, int radix) throws IOException {
        String pattern = radix == 16 ? "[0-9a-fA-F]{1,8}" : "[0-9]{1,8}";
        if (!value.matches(pattern)) throw new IOException("Invalid HTTP body length");
        long size = Long.parseLong(value, radix);
        if (size > limit) throw new IOException("HTTP body too large");
        return (int) size;
    }

    private static byte[] readExactly(InputStream input, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) throw new EOFException("Truncated HTTP body");
            if (read == 0) throw new IOException("Empty HTTP read");
            offset += read;
        }
        return bytes;
    }

    private static byte[] readToEnd(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) throw new IOException("Empty HTTP read");
            if (count > MAX_BODY - body.size()) throw new IOException("HTTP body too large");
            body.write(buffer, 0, count);
        }
        return body.toByteArray();
    }

    public boolean isClosed() { return closed.get(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close(); } catch (IOException ignored) {}
            synchronized (cryptoLock) {
                if (encoder != null) encoder.close();
                if (decoder != null) decoder.close();
                if (responseInput instanceof RecordInput records) records.wipe();
            }
        }
    }

    /** Keeps authenticated leftovers when a record contains multiple or partial HTTP messages. */
    private static final class RecordInput extends InputStream {
        private final InputStream source;
        private final LelinkSecureRecord.Decoder decoder;
        private byte[] pending = new byte[0];
        private int offset;
        RecordInput(InputStream source, LelinkSecureRecord.Decoder decoder) {
            this.source = source;
            this.decoder = decoder;
        }
        private void fill() throws IOException {
            while (offset == pending.length) {
                wipe();
                pending = decoder.read(source);
            }
        }
        @Override public synchronized int read() throws IOException {
            fill();
            return pending[offset++] & 0xff;
        }
        @Override public synchronized int read(byte[] target, int start, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(start, length, target.length);
            if (length == 0) return 0;
            fill();
            int count = Math.min(length, pending.length - offset);
            System.arraycopy(pending, offset, target, start, count);
            offset += count;
            return count;
        }
        synchronized void wipe() {
            Arrays.fill(pending, (byte) 0);
            pending = new byte[0];
            offset = 0;
        }
    }
}
