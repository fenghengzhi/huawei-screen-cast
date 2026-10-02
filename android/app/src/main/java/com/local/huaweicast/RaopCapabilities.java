package com.local.huaweicast;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Local discovery hints only; a compatible advertisement does not confirm audio playback. */
public final class RaopCapabilities {
    private static final int MAX_TXT_ENTRIES = 64;
    private static final int MAX_TEXT_BYTES = 255;
    private static final Set<String> KEYS = Set.of("cn", "et", "sr", "ss", "ch", "tp", "pw");
    private final boolean aacEld, unencrypted, udp, passwordRequired, passwordRequirementKnown;
    private final int sampleRate, sampleSize, channels;

    private RaopCapabilities(boolean aacEld, boolean unencrypted, boolean udp,
                             boolean passwordRequired, boolean passwordRequirementKnown,
                             int sampleRate, int sampleSize, int channels) {
        this.aacEld = aacEld;
        this.unencrypted = unencrypted;
        this.udp = udp;
        this.passwordRequired = passwordRequired;
        this.passwordRequirementKnown = passwordRequirementKnown;
        this.sampleRate = sampleRate;
        this.sampleSize = sampleSize;
        this.channels = channels;
    }

    public static RaopCapabilities from(Map<String, byte[]> attributes) {
        if (attributes == null || attributes.size() > MAX_TXT_ENTRIES) {
            throw new IllegalArgumentException("Invalid RAOP TXT record count");
        }
        Map<String, String> accepted = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            if (entry.getKey() == null) throw new IllegalArgumentException("Invalid RAOP TXT key");
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!KEYS.contains(key)) continue;
            if (accepted.containsKey(key)) throw new IllegalArgumentException("Duplicate RAOP TXT field: " + key);
            byte[] bytes = entry.getValue();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Invalid RAOP TXT length: " + key);
            }
            for (byte value : bytes) {
                if (value < 0x20 || value > 0x7e) throw new IllegalArgumentException("Invalid RAOP TXT character: " + key);
            }
            accepted.put(key, new String(bytes, StandardCharsets.US_ASCII));
        }
        String password = accepted.get("pw");
        if (password != null && !password.equalsIgnoreCase("true") && !password.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Invalid RAOP password flag");
        }
        return new RaopCapabilities(numericListContains(accepted.get("cn"), 3, "cn"),
                numericListContains(accepted.get("et"), 0, "et"), transportsContainUdp(accepted.get("tp")),
                "true".equalsIgnoreCase(password), password != null, numberOrUnknown(accepted.get("sr"), "sr", 384000),
                numberOrUnknown(accepted.get("ss"), "ss", 64), numberOrUnknown(accepted.get("ch"), "ch", 32));
    }

    private static boolean numericListContains(String value, int expected, String field) {
        if (value == null) return false;
        String[] items = value.split(",", -1);
        if (items.length > 32) throw new IllegalArgumentException("Too many RAOP values: " + field);
        boolean found = false;
        for (String item : items) if (number(item, field, 65535) == expected) found = true;
        return found;
    }

    private static boolean transportsContainUdp(String value) {
        if (value == null) return false;
        String[] items = value.split(",", -1);
        if (items.length > 32) throw new IllegalArgumentException("Too many RAOP transports");
        boolean found = false;
        for (String item : items) {
            if (item.isEmpty()) throw new IllegalArgumentException("Invalid RAOP transport");
            for (int i = 0; i < item.length(); i++) {
                char c = item.charAt(i);
                if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')) {
                    throw new IllegalArgumentException("Invalid RAOP transport");
                }
            }
            if (item.equalsIgnoreCase("UDP")) found = true;
        }
        return found;
    }

    private static int numberOrUnknown(String value, String field, int max) {
        if (value == null) return 0;
        int parsed = number(value, field, max);
        if (parsed == 0) throw new IllegalArgumentException("Invalid RAOP value: " + field);
        return parsed;
    }

    private static int number(String value, String field, int max) {
        if (value.isEmpty() || value.length() > 6) throw new IllegalArgumentException("Invalid RAOP number: " + field);
        int parsed = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("Invalid RAOP number: " + field);
            parsed = parsed * 10 + c - '0';
            if (parsed > max) throw new IllegalArgumentException("Invalid RAOP range: " + field);
        }
        return parsed;
    }

    public boolean supportsAacEld() { return aacEld; }
    public boolean supportsUnencrypted() { return unencrypted; }
    public boolean supportsUdp() { return udp; }
    public boolean passwordRequired() { return passwordRequired; }
    public int sampleRate() { return sampleRate; }
    public int sampleSize() { return sampleSize; }
    public int channels() { return channels; }
    public boolean eligible() {
        return aacEld && unencrypted && udp && passwordRequirementKnown && !passwordRequired
                && (sampleRate == 44100 || sampleRate == 48000) && sampleSize == 16 && channels == 2;
    }
    public String unsupportedReason() {
        if (passwordRequired) return "接收端音频需要密码认证，当前未支持";
        if (!passwordRequirementKnown) return "接收端未广播音频认证要求";
        if (!aacEld) return "接收端未广播 AAC-ELD 音频支持";
        if (!unencrypted) return "接收端未广播无加密音频支持";
        if (!udp) return "接收端未广播 UDP 音频支持";
        if (sampleRate != 44100 && sampleRate != 48000) return "接收端未广播兼容的音频采样率";
        if (sampleSize != 16 || channels != 2) return "接收端未广播 16 位双声道音频支持";
        return "";
    }
}
