package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class LelinkEndpointTest {
    private static Map<String, byte[]> txt(String... values) {
        Map<String, byte[]> attributes = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) attributes.put(values[i], values[i + 1].getBytes(StandardCharsets.UTF_8));
        return attributes;
    }
    private static LelinkEndpoint endpoint(Map<String, byte[]> attributes) throws Exception {
        return LelinkEndpoint.from("中国电信机顶盒", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 12}), 7100, attributes);
    }
    private static void rejected(Runnable parse) {
        try { parse.run(); fail("Expected invalid discovery data to be rejected"); }
        catch (IllegalArgumentException expected) {}
    }

    @Test public void advertisedControlPortsHaveExplicitPrecedence() throws Exception {
        assertEquals(7100, endpoint(Map.of()).controlPort());
        assertEquals(6000, endpoint(txt("remote", "6000")).controlPort());
        assertEquals(7000, endpoint(txt("remote", "6000", "LELINKPORT", "7000")).controlPort());
        assertFalse(endpoint(Map.of()).mirrorPort().isPresent());
        assertEquals(7200, endpoint(txt("mirror", "7200")).mirrorPort().getAsInt());
        assertFalse(endpoint(Map.of()).raopPort().isPresent());
        assertEquals(52244, endpoint(txt("raop", "52244")).raopPort().getAsInt());
        assertTrue(endpoint(Map.of()).summary().contains("未广播镜像端口"));
    }

    @Test public void invalidAdvertisedPortsNeverFallBack() throws Exception {
        InetAddress host = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        for (String port : new String[]{"", "0", "65536", "-1", "+12", " 12", "12 ", "12x", "１２", "999999999"}) {
            rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("lelinkport", port, "remote", "6000")));
            rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("mirror", port)));
            rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("raop", port)));
        }
        rejected(() -> LelinkEndpoint.from("receiver", host, 0, Map.of()));
        rejected(() -> LelinkEndpoint.from("receiver", host, 65536, txt("remote", "6000")));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("lelinkport", "6000", "remote", "invalid")));
        assertEquals(1, endpoint(txt("remote", "1")).controlPort());
        assertEquals(65535, endpoint(txt("remote", "65535")).controlPort());
        assertEquals(1, endpoint(txt("raop", "1")).raopPort().getAsInt());
        assertEquals(65535, endpoint(txt("raop", "65535")).raopPort().getAsInt());
    }

    @Test public void recognizedKeysAreCaseInsensitiveButDuplicatesAreRejected() throws Exception {
        InetAddress host = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("remote", "6000", "REMOTE", "6001")));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("version", "1", "VERSION", "2")));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("raop", "6000", "RAOP", "6001")));
        assertEquals(6000, endpoint(txt("RAOP", "6000")).raopPort().getAsInt());
        assertEquals("1.0", endpoint(txt("VERSION", "1.0")).metadata().get("version"));
    }

    @Test public void metadataIsAllowlistedAndDetachedFromMutableTxt() throws Exception {
        Map<String, byte[]> attributes = txt("version", "1.0", "ver", "2", "hmd", "1", "features", "0x12", "width", "1920", "height", "1080",
                "uid", "private-id", "mac", "private-mac", "channel", "secret", "token", "secret", "mirror", "7200");
        LelinkEndpoint endpoint = endpoint(attributes);
        assertEquals(6, endpoint.metadata().size());
        assertFalse(endpoint.metadata().containsKey("uid"));
        assertFalse(endpoint.metadata().containsKey("mac"));
        assertFalse(endpoint.metadata().containsKey("channel"));
        assertFalse(endpoint.metadata().containsKey("mirror"));
        attributes.get("version")[0] = '9';
        attributes.clear();
        assertEquals("1.0", endpoint.metadata().get("version"));
        try { endpoint.metadata().put("version", "2"); fail(); }
        catch (UnsupportedOperationException expected) {}
    }

    @Test public void rejectsInvalidTextWithoutEchoingItsValue() throws Exception {
        InetAddress host = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, Map.of("version", new byte[]{(byte) 0xc3, 0x28})));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, Map.of("version", new byte[256])));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("version", "1\r\nInjected: data")));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, txt("version", "1\u202e")));
        rejected(() -> LelinkEndpoint.from(" ", host, 7100, Map.of()));
        rejected(() -> LelinkEndpoint.from("x".repeat(256), host, 7100, Map.of()));
        rejected(() -> LelinkEndpoint.from("bad\ud800", host, 7100, Map.of()));
        assertEquals("投屏电视", LelinkEndpoint.from("投屏电视", host, 7100, Map.of()).title());
        assertEquals("TV\ud83d\udcfa", LelinkEndpoint.from("TV\ud83d\udcfa", host, 7100, Map.of()).title());
    }

    @Test public void rejectsExcessiveAndNullTxt() throws Exception {
        InetAddress host = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) many.put("unknown" + i, new byte[0]);
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, many));
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, null));
        Map<String, byte[]> missing = new LinkedHashMap<>();
        missing.put("version", null);
        rejected(() -> LelinkEndpoint.from("receiver", host, 7100, missing));
    }

    @Test public void privateAndLinkLocalAddressesAreAcceptedWithoutDnsLookup() throws Exception {
        for (String literal : new String[]{"10.1.2.3", "172.16.1.2", "192.168.1.2", "169.254.1.2", "fd12:3456::1", "fc00::1", "fe80::1", "fec0::1"}) {
            InetAddress host = InetAddress.getByName(literal);
            assertEquals(host, LelinkEndpoint.from("receiver", host, 7100, Map.of()).address());
        }
        LelinkEndpoint v6 = LelinkEndpoint.from("receiver", InetAddress.getByName("fd12:3456::1"), 7100, Map.of());
        assertTrue(v6.key().startsWith("["));
        assertTrue(v6.key().endsWith("]:7100"));
    }

    @Test public void publicLoopbackUnspecifiedAndMulticastAddressesAreRejected() throws Exception {
        for (String literal : new String[]{"8.8.8.8", "172.15.1.2", "127.0.0.1", "0.0.0.0", "224.0.0.251", "255.255.255.255", "2001:4860:4860::8888", "::1", "::", "ff02::fb"}) {
            InetAddress host = InetAddress.getByName(literal);
            rejected(() -> LelinkEndpoint.from("receiver", host, 7100, Map.of()));
        }
        rejected(() -> LelinkEndpoint.from("receiver", null, 7100, Map.of()));
    }

    @Test public void identityUsesAddressAndControlPortNotAdvertisedName() throws Exception {
        InetAddress host = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        LelinkEndpoint first = LelinkEndpoint.from("one", host, 7100, Map.of());
        LelinkEndpoint duplicate = LelinkEndpoint.from("two", host, 7100, Map.of());
        LelinkEndpoint otherPort = LelinkEndpoint.from("one", host, 7101, Map.of());
        assertEquals("10.0.0.1:7100", first.key());
        assertEquals(first.key(), duplicate.key());
        assertNotEquals(first.key(), otherPort.key());
    }
}
