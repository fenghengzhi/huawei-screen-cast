package com.local.huaweicast;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class ProtocolTest {
    @Test public void rangesSupportVideoSeeking() {
        assertEquals(new ByteRange(0, 99, false), ByteRange.parse(null, 100));
        assertEquals(new ByteRange(20, 99, true), ByteRange.parse("bytes=20-", 100));
        assertEquals(new ByteRange(80, 99, true), ByteRange.parse("bytes=-20", 100));
        assertEquals(new ByteRange(2, 99, true), ByteRange.parse("bytes=2-500", 100));
        assertEquals(11, ByteRange.parse("bytes=10-20", 100).length());
    }
    @Test public void invalidRangesAreRejected() {
        for (String range : new String[]{"bytes=100-", "bytes=20-10", "bytes=-0", "bytes=1-2,3-4", "items=0-1", "bytes=a-b"}) {
            try { ByteRange.parse(range, 100); fail(range); } catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void ssdpHeaderNamesAreCaseInsensitive() { assertEquals("http://192.168.1.3:80/device.xml", Dlna.location("HTTP/1.1 200 OK\r\nlOcAtIoN: http://192.168.1.3:80/device.xml\r\n")); }
    @Test public void descriptionResolvesRelativeControlAndVersion() throws Exception {
        String xml = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><URLBase>http://192.168.1.3:8000/</URLBase><device><friendlyName>中国电信机顶盒</friendlyName><manufacturer>Test</manufacturer><UDN>uuid:tv</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType><controlURL>/av/control</controlURL></service></serviceList></device></root>";
        Dlna.Device device = Dlna.parseDescription("http://192.168.1.3/device.xml", xml.getBytes(StandardCharsets.UTF_8));
        assertEquals("中国电信机顶盒", device.name()); assertEquals("http://192.168.1.3:8000/av/control", device.controlUrl()); assertTrue(device.serviceType().endsWith(":2"));
    }
    @Test public void xmlEntityExpansionIsRejected() {
        try { Dlna.parseDescription("http://localhost/", "<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>".getBytes(StandardCharsets.UTF_8)); fail(); } catch (Exception expected) {}
    }
    @Test public void mediaMetadataEscapesNamesAndUrls() { String metadata = Dlna.metadata("A&B <电影>", "video/mp4", "http://host/file?a=1&b=2", 123); assertTrue(metadata.contains("A&amp;B &lt;电影&gt;")); assertTrue(metadata.contains("a=1&amp;b=2")); assertTrue(metadata.contains("object.item.videoItem")); }
}
