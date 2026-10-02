package com.local.huaweicast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Read-only discovery follow-up; never creates a mirror session or supplies credentials. */
public final class LelinkProbe implements AutoCloseable {
    static final int MAX_HEADERS = 8192;
    static final int MAX_BODY = 32768;
    private static final int TIMEOUT_MS = 2000;
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(8);
    private final SocketBinder binder;
    private final Set<Socket> sockets = new HashSet<>();
    private volatile boolean closed;

    public interface SocketBinder { void bind(Socket socket) throws IOException; }
    public record Report(String summary, boolean legacyMirrorAvailable, int width, int height,
                         int controlStatus, int streamStatus) {}
    record Response(int status, byte[] body) {}
    record Capabilities(int width, int height) {}

    public LelinkProbe(SocketBinder binder) {
        if (binder == null) throw new IllegalArgumentException("Socket binder is required");
        this.binder = binder;
    }

    public Report inspect(LelinkEndpoint endpoint) {
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int control = 0;
        int stream = 0;
        try {
            if (endpoint == null) throw new IOException("Missing receiver");
            control = request(endpoint.address(), endpoint.controlPort(), true, deadline).status();
            if (requiresAuthorization(control)) return unavailable("接收端要求授权，已停止探测", control, stream);
            if (control != 200) return unavailable("控制端口未接受只读探测 (" + control + ")", control, stream);
            if (endpoint.mirrorPort().isEmpty()) {
                return unavailable("控制端口响应正常，但未广播镜像端口；未验证镜像能力", control, stream);
            }
            Response response = request(endpoint.address(), endpoint.mirrorPort().getAsInt(), false, deadline);
            stream = response.status();
            if (requiresAuthorization(stream)) return unavailable("镜像端口要求授权，已停止探测", control, stream);
            if (stream != 200) return unavailable("镜像能力端点未接受只读探测 (" + stream + ")", control, stream);
            Capabilities capabilities = parseCapabilities(response.body());
            return new Report("旧式 AirPlay 兼容能力端点已响应：" + capabilities.width() + " x "
                    + capabilities.height() + "；尚未验证镜像握手，不代表支持完整乐联镜像", true,
                    capabilities.width(), capabilities.height(), control, stream);
        } catch (SocketTimeoutException error) {
            return unavailable("探测超时，未验证镜像能力", control, stream);
        } catch (Exception error) {
            return unavailable(closed ? "探测已取消" : "探测未完成或响应格式不受支持，未验证镜像能力", control, stream);
        }
    }

    private static boolean requiresAuthorization(int status) { return status == 401 || status == 403; }
    private static Report unavailable(String summary, int control, int stream) {
        return new Report(summary, false, 0, 0, control, stream);
    }

    // A numeric InetAddress and an advertised port are used directly: no DNS, redirects, or port scanning.
    Response request(InetAddress address, int port, boolean rtsp, long deadline) throws IOException {
        Socket socket = new Socket();
        synchronized (sockets) {
            if (closed) { socket.close(); throw new IOException("Cancelled"); }
            sockets.add(socket);
        }
        try (socket) {
            binder.bind(socket);
            socket.connect(new InetSocketAddress(address, port), remainingTimeout(deadline));
            String host = address.getHostAddress();
            if (host == null) throw new IOException("Missing receiver address");
            if (host.contains(":")) host = "[" + host + "]";
            String message = rtsp ? "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: HuaweiCast/Probe\r\n\r\n"
                    : "GET /stream.xml HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nUser-Agent: HuaweiCast/Probe\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(message.getBytes(StandardCharsets.US_ASCII));
            InputStream source = socket.getInputStream();
            InputStream timed = new InputStream() {
                @Override public int read() throws IOException {
                    socket.setSoTimeout(remainingTimeout(deadline));
                    return source.read();
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    socket.setSoTimeout(remainingTimeout(deadline));
                    return source.read(buffer, offset, length);
                }
            };
            return readResponse(timed, !rtsp, rtsp);
        } finally {
            synchronized (sockets) { sockets.remove(socket); }
        }
    }

    private static int remainingTimeout(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("Probe deadline reached");
        return (int) Math.max(1, Math.min(TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    static Response readResponse(InputStream input, boolean bodyRequested, boolean rtsp) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int ending = 0;
        byte[] separator = {'\r', '\n', '\r', '\n'};
        while (ending < separator.length) {
            int value = input.read();
            if (value < 0) throw new EOFException("Truncated headers");
            if (header.size() == MAX_HEADERS) throw new IOException("Headers too large");
            header.write(value);
            ending = value == separator[ending] ? ending + 1 : value == '\r' ? 1 : 0;
        }
        String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !(rtsp ? "RTSP/1.0".equals(status[0])
                : "HTTP/1.0".equals(status[0]) || "HTTP/1.1".equals(status[0]))
                || !status[1].matches("[1-5][0-9][0-9]")) throw new IOException("Invalid status");
        int code = Integer.parseInt(status[1]);
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0 || Character.isWhitespace(lines[i].charAt(0))) throw new IOException("Invalid header");
            String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (fields.put(key, value) != null && (key.equals("content-length") || key.equals("transfer-encoding"))) {
                throw new IOException("Duplicate framing header");
            }
        }
        if (!bodyRequested || code != 200) return new Response(code, new byte[0]);
        String encoding = fields.get("transfer-encoding");
        if (encoding != null) {
            if (!encoding.equalsIgnoreCase("chunked") || fields.containsKey("content-length")) {
                throw new IOException("Unsupported transfer encoding");
            }
            return new Response(code, readChunks(input, MAX_HEADERS - header.size()));
        }
        String length = fields.get("content-length");
        if (length != null) return new Response(code, readExactly(input, parseLength(length, MAX_BODY)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (body.size() + count > MAX_BODY) throw new IOException("Body too large");
            body.write(buffer, 0, count);
        }
        return new Response(code, body.toByteArray());
    }

    private static int parseLength(String value, int limit) throws IOException {
        if (value.isEmpty() || value.length() > 8 || !value.matches("[0-9]+")) throw new IOException("Invalid length");
        int length = Integer.parseInt(value);
        if (length > limit) throw new IOException("Body too large");
        return length;
    }

    private static byte[] readExactly(InputStream input, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int size = input.read(bytes, offset, count - offset);
            if (size < 0) throw new EOFException("Truncated body");
            if (size == 0) throw new IOException("Empty read");
            offset += size;
        }
        return bytes;
    }

    private static byte[] readChunks(InputStream input, int framingLimit) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int framingBytes = 0;
        while (true) {
            String line = readLine(input, framingLimit - framingBytes);
            framingBytes += line.length() + 2;
            int extension = line.indexOf(';');
            String sizeText = extension < 0 ? line : line.substring(0, extension);
            if (!sizeText.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid chunk size");
            long size = Long.parseLong(sizeText, 16);
            if (size > MAX_BODY - body.size()) throw new IOException("Body too large");
            if (size == 0) {
                do {
                    line = readLine(input, framingLimit - framingBytes);
                    framingBytes += line.length() + 2;
                } while (!line.isEmpty());
                return body.toByteArray();
            }
            byte[] chunk = readExactly(input, (int) size);
            body.write(chunk, 0, chunk.length);
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("Invalid chunk ending");
            framingBytes += 2;
        }
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (line.size() + 2 > limit) throw new IOException("Chunk framing too large");
            int value = input.read();
            if (value < 0) throw new EOFException("Truncated chunk framing");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid chunk framing");
                return line.toString(StandardCharsets.US_ASCII.name());
            }
            if (value > 127 || value < 32) throw new IOException("Invalid chunk character");
            line.write(value);
        }
    }

    static Capabilities parseCapabilities(byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_BODY) throw new IOException("Invalid XML length");
        for (byte value : bytes) if (value == 0) throw new IOException("Unsupported XML encoding");
        String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (source.contains("<!ENTITY")) throw new IOException("Entity declarations are not allowed");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
        // Apple plist DOCTYPE is common. Resolve nothing outside this bounded response.
        for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { factory.setFeature(feature, false); }
            catch (javax.xml.parsers.ParserConfigurationException ignored) {}
        }
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
        });
        Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if (!root.getTagName().equals("plist")) throw new IOException("Expected plist");
        List<Element> children = elements(root);
        if (children.size() != 1 || !children.get(0).getTagName().equals("dict")) throw new IOException("Expected dictionary");
        List<Element> entries = elements(children.get(0));
        if (entries.size() % 2 != 0 || entries.size() > 128) throw new IOException("Invalid dictionary");
        Map<String, String> fields = new HashMap<>();
        Set<String> allowed = Set.of("width", "height", "happycast", "version");
        for (int i = 0; i < entries.size(); i += 2) {
            Element key = entries.get(i);
            if (!key.getTagName().equals("key") || !elements(key).isEmpty()) throw new IOException("Invalid key");
            String name = key.getTextContent().trim();
            if (!allowed.contains(name)) continue;
            Element value = entries.get(i + 1);
            if (!Set.of("integer", "real", "string").contains(value.getTagName()) || !elements(value).isEmpty()) {
                throw new IOException("Invalid scalar");
            }
            String text = value.getTextContent().trim();
            if (text.length() > 64 || fields.put(name, text) != null) throw new IOException("Invalid field");
        }
        return new Capabilities(dimension(fields.get("width")), dimension(fields.get("height")));
    }

    private static List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) result.add(element);
        }
        return result;
    }

    private static int dimension(String value) throws IOException {
        if (value == null || !value.matches("[0-9]{1,5}(\\.[0-9]{1,8})?")) throw new IOException("Invalid dimension");
        double number = Double.parseDouble(value);
        if (number < 1 || number > 8192 || number != Math.rint(number)) throw new IOException("Invalid dimension");
        return (int) number;
    }

    @Override public void close() {
        synchronized (sockets) {
            closed = true;
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
            sockets.clear();
        }
    }
}
