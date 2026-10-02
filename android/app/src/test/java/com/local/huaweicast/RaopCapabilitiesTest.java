package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class RaopCapabilitiesTest {
    private static Map<String, byte[]> txt(String... values) {
        Map<String, byte[]> attributes = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) attributes.put(values[i], values[i + 1].getBytes(StandardCharsets.UTF_8));
        return attributes;
    }
    private static Map<String, byte[]> compatible() {
        return txt("cn", "0,1,2,3", "et", "0,1,4", "sr", "44100", "ss", "16", "ch", "2", "tp", "UDP,TCP", "pw", "false");
    }
    private static RaopCapabilities with(String key, String value) {
        Map<String, byte[]> data = compatible();
        data.put(key, value.getBytes(StandardCharsets.UTF_8));
        return RaopCapabilities.from(data);
    }
    private static void rejected(Runnable operation) {
        try { operation.run(); fail("Expected invalid RAOP data to be rejected"); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void acceptsOnlyTheImplementedAudioCombination() {
        RaopCapabilities audio = RaopCapabilities.from(compatible());
        assertTrue(audio.eligible());
        assertTrue(audio.supportsAacEld());
        assertTrue(audio.supportsUnencrypted());
        assertTrue(audio.supportsUdp());
        assertFalse(audio.passwordRequired());
        assertEquals(44100, audio.sampleRate());
        assertEquals(16, audio.sampleSize());
        assertEquals(2, audio.channels());
        assertEquals("", audio.unsupportedReason());
        assertTrue(with("sr", "48000").eligible());
        assertTrue(with("cn", "3").eligible());
        assertTrue(with("et", "0").eligible());
        assertTrue(with("tp", "udp").eligible());
    }

    @Test public void missingRequiredCapabilitiesNeverAcquireDefaults() {
        for (String key : new String[]{"cn", "et", "sr", "ss", "ch", "tp", "pw"}) {
            Map<String, byte[]> data = compatible();
            data.remove(key);
            RaopCapabilities audio = RaopCapabilities.from(data);
            assertFalse("Missing " + key, audio.eligible());
            assertFalse(audio.unsupportedReason().isEmpty());
        }
        RaopCapabilities empty = RaopCapabilities.from(Map.of());
        assertFalse(empty.eligible());
        assertEquals(0, empty.sampleRate());
        assertEquals(0, empty.sampleSize());
        assertEquals(0, empty.channels());
    }

    @Test public void passwordAndEncryptionRequirementsAreNotBypassed() {
        RaopCapabilities password = with("pw", "true");
        assertTrue(password.passwordRequired());
        assertFalse(password.eligible());
        assertTrue(password.unsupportedReason().contains("密码"));
        RaopCapabilities encrypted = with("et", "1,4");
        assertFalse(encrypted.supportsUnencrypted());
        assertFalse(encrypted.eligible());
        assertTrue(encrypted.unsupportedReason().contains("加密"));
        assertFalse(with("pw", "TRUE").eligible());
        assertTrue(with("pw", "FALSE").eligible());
    }

    @Test public void unsupportedCodecTransportOrPcmShapeIsNotEligible() {
        for (String[] field : new String[][]{{"cn", "0,1,2"}, {"cn", "13"}, {"et", "10"},
                {"tp", "TCP"}, {"sr", "22050"}, {"sr", "96000"}, {"ss", "24"}, {"ch", "1"}}) {
            RaopCapabilities audio = with(field[0], field[1]);
            assertFalse(field[0], audio.eligible());
            assertFalse(audio.unsupportedReason().isEmpty());
        }
    }

    @Test public void rejectsMalformedNumericFieldsAndLists() {
        for (String value : new String[]{"", "-1", "+3", " 3", "3 ", "3x", "３", "1,,3", "3,", ",3", "65536", "9999999999"}) {
            rejected(() -> with("cn", value));
            rejected(() -> with("et", value));
        }
        rejected(() -> with("cn", "1,".repeat(32) + "3"));
        for (String value : new String[]{"0", "-1", "44100,48000", "44100 ", "384001", "9999999999"}) rejected(() -> with("sr", value));
        rejected(() -> with("ss", "65"));
        rejected(() -> with("ch", "33"));
    }

    @Test public void rejectsMalformedTransportsAndPasswordFlags() {
        for (String value : new String[]{"", ",UDP", "UDP,", "UDP,,TCP", " UDP", "UDP2", "UDP/TCP"}) rejected(() -> with("tp", value));
        rejected(() -> with("tp", "TCP,".repeat(32) + "UDP"));
        for (String value : new String[]{"", "yes", "no", "0", "1", " false", "false "}) rejected(() -> with("pw", value));
    }

    @Test public void recognizesCaseInsensitiveKeysAndRejectsDuplicates() {
        Map<String, byte[]> upper = new LinkedHashMap<>();
        compatible().forEach((key, value) -> upper.put(key.toUpperCase(java.util.Locale.ROOT), value));
        assertTrue(RaopCapabilities.from(upper).eligible());
        Map<String, byte[]> duplicate = compatible();
        duplicate.put("CN", new byte[]{'3'});
        rejected(() -> RaopCapabilities.from(duplicate));
    }

    @Test public void boundsTextAndRejectsInjectionOrMalformedBytes() {
        for (byte[] value : new byte[][]{new byte[256], new byte[]{(byte) 0xc3, 0x28}, new byte[]{'3', '\n'}, new byte[]{'3', 0x7f}, null}) {
            Map<String, byte[]> data = compatible(); data.put("cn", value);
            rejected(() -> RaopCapabilities.from(data));
        }
        rejected(() -> with("cn", "3\r\nAuthorization: secret"));
        try { with("pw", "private-secret"); fail(); }
        catch (IllegalArgumentException error) { assertFalse(error.getMessage().contains("private-secret")); }
    }

    @Test public void rejectsNullAndExcessiveRecords() {
        rejected(() -> RaopCapabilities.from(null));
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) many.put("unknown" + i, new byte[0]);
        rejected(() -> RaopCapabilities.from(many));
        Map<String, byte[]> data = compatible(); data.put(null, new byte[0]);
        rejected(() -> RaopCapabilities.from(data));
    }

    @Test public void storesOnlyDetachedPrimitiveCapabilitiesNotIdentifiers() {
        Map<String, byte[]> data = compatible();
        data.putAll(txt("uid", "private-id", "mac", "private-mac", "token", "private-secret"));
        RaopCapabilities audio = RaopCapabilities.from(data);
        data.get("cn")[0] = '9'; data.clear();
        assertTrue(audio.eligible());
        for (java.lang.reflect.Field field : RaopCapabilities.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) assertTrue("Stored " + field.getName(), field.getType().isPrimitive());
        }
    }
}
