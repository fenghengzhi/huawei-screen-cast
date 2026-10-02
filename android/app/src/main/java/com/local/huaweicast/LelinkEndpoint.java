package com.local.huaweicast;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/** Sanitized discovery data, not a claim that a mirror session is supported. */
public final class LelinkEndpoint {
    private static final int MAX_TEXT_BYTES = 255;
    private static final int MAX_TXT_ENTRIES = 64;
    private static final Set<String> METADATA_KEYS = Set.of("version", "ver", "hmd", "features", "width", "height");
    private static final Set<String> PORT_KEYS = Set.of("lelinkport", "remote", "mirror");

    private final String name;
    private final InetAddress address;
    private final int controlPort;
    private final OptionalInt mirrorPort;
    private final Map<String, String> metadata;

    private LelinkEndpoint(String name, InetAddress address, int controlPort,
                           OptionalInt mirrorPort, Map<String, String> metadata) {
        this.name = name;
        this.address = address;
        this.controlPort = controlPort;
        this.mirrorPort = mirrorPort;
        this.metadata = Map.copyOf(metadata);
    }

    public static LelinkEndpoint from(String serviceName, InetAddress address, int srvPort,
                                      Map<String, byte[]> attributes) {
        validateAddress(address);
        validatePort(srvPort, "SRV");
        String name = validateText(serviceName, "service name").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Missing service name");
        if (attributes == null || attributes.size() > MAX_TXT_ENTRIES) {
            throw new IllegalArgumentException("Invalid TXT record count");
        }

        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey == null) throw new IllegalArgumentException("Invalid TXT key");
            String key = rawKey.toLowerCase(Locale.ROOT);
            if (!PORT_KEYS.contains(key) && !METADATA_KEYS.contains(key)) continue;
            if (accepted.containsKey(key)) throw new IllegalArgumentException("Duplicate TXT field: " + key);
            accepted.put(key, decode(entry.getValue(), key));
        }

        // Invalid advertised ports are not silently replaced with another endpoint.
        for (String key : PORT_KEYS) if (accepted.containsKey(key)) parsePort(accepted.get(key), key);
        int controlPort = accepted.containsKey("lelinkport") ? parsePort(accepted.get("lelinkport"), "lelinkport")
                : accepted.containsKey("remote") ? parsePort(accepted.get("remote"), "remote") : srvPort;
        OptionalInt mirrorPort = accepted.containsKey("mirror")
                ? OptionalInt.of(parsePort(accepted.get("mirror"), "mirror")) : OptionalInt.empty();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : METADATA_KEYS) {
            if (accepted.containsKey(key)) metadata.put(key, accepted.get(key));
        }
        return new LelinkEndpoint(name, address, controlPort, mirrorPort, metadata);
    }

    private static void validateAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress()) throw new IllegalArgumentException("A local receiver address is required");
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = address instanceof Inet6Address && (bytes[0] & 0xfe) == 0xfc;
        if (!address.isSiteLocalAddress() && !address.isLinkLocalAddress() && !uniqueLocalV6) {
            throw new IllegalArgumentException("Receiver address must be private or link-local");
        }
    }

    private static String decode(byte[] value, String field) {
        if (value == null || value.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("Invalid TXT field length: " + field);
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
            return validateText(decoded, field);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid UTF-8 TXT field: " + field);
        }
    }

    private static String validateText(String value, String field) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Invalid text length: " + field);
        }
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if ((codePoint >= 0xd800 && codePoint <= 0xdfff) || Character.isISOControl(codePoint)
                    || Character.getType(codePoint) == Character.FORMAT) {
                throw new IllegalArgumentException("Invalid text character: " + field);
            }
            i += Character.charCount(codePoint);
        }
        return value;
    }

    private static int parsePort(String value, String field) {
        if (value.isEmpty() || value.length() > 5) throw new IllegalArgumentException("Invalid port: " + field);
        int port = 0;
        for (int i = 0; i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') throw new IllegalArgumentException("Invalid port: " + field);
            port = port * 10 + digit - '0';
        }
        validatePort(port, field);
        return port;
    }

    private static void validatePort(int port, String field) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + field);
    }

    public String name() { return name; }
    public InetAddress address() { return address; }
    public int controlPort() { return controlPort; }
    public OptionalInt mirrorPort() { return mirrorPort; }
    public Map<String, String> metadata() { return metadata; }
    public String key() {
        String host = address.getHostAddress();
        return (address instanceof Inet6Address ? "[" + host + "]" : host) + ":" + controlPort;
    }
    public String title() { return name; }
    public String summary() {
        return "乐联 · " + key() + (mirrorPort.isPresent()
                ? " · 广播镜像端口 " + mirrorPort.getAsInt() : " · 未广播镜像端口");
    }
}
