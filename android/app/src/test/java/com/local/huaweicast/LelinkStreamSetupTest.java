package com.local.huaweicast;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.PropertyListParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class LelinkStreamSetupTest {
    @Test public void videoRequestSelectsEncryptedNativeTcpMirrorInBinaryPlist() throws Exception {
        byte[] request = LelinkStreamSetup.videoRequest(34567);
        assertEquals("bplist00", new String(request, 0, 8, StandardCharsets.US_ASCII));
        NSDictionary root = (NSDictionary) PropertyListParser.parse(request);
        assertEquals(4, root.count());
        assertEquals(1, number(root, "encrypt-mode"));
        assertEquals(1, number(root, "mst"));
        assertEquals(34567, number(root, "timing-port"));
        NSArray streams = (NSArray) root.objectForKey("streams");
        assertEquals(1, streams.count());
        NSDictionary stream = (NSDictionary) streams.objectAtIndex(0);
        assertEquals(2, stream.count());
        assertEquals(97, number(stream, "type"));
        assertEquals(0, number(stream, "mirror-tunnel"));
    }

    @Test public void audioRequestSelectsEncryptedUdpEldAtSupportedSampleRates() throws Exception {
        for (int rate : new int[]{44100, 48000}) {
            byte[] request = LelinkStreamSetup.audioRequest(rate, 30001, 30002);
            assertEquals("bplist00", new String(request, 0, 8, StandardCharsets.US_ASCII));
            NSDictionary root = (NSDictionary) PropertyListParser.parse(request);
            assertEquals(4, root.count());
            assertEquals(1, number(root, "encrypt-mode"));
            assertEquals(1, number(root, "ast"));
            assertEquals(30002, number(root, "timing-port"));
            NSArray streams = (NSArray) root.objectForKey("streams");
            assertEquals(1, streams.count());
            NSDictionary stream = (NSDictionary) streams.objectAtIndex(0);
            assertEquals(3, stream.count());
            assertEquals(96, number(stream, "type"));
            assertEquals(rate, number(stream, "sample-rate"));
            assertEquals(212, number(stream, "sample-format"));
            assertNull(stream.objectForKey("control-port"));
            assertNull(root.objectForKey("control-port"));
        }
    }

    @Test public void requestsRejectInvalidLocalPortsAndUnimplementedAudioRates() {
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0, 65536, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> LelinkStreamSetup.videoRequest(invalid));
            assertThrows(IllegalArgumentException.class, () -> LelinkStreamSetup.audioRequest(44100, invalid, 5000));
            assertThrows(IllegalArgumentException.class, () -> LelinkStreamSetup.audioRequest(44100, 5000, invalid));
        }
        for (int rate : new int[]{-1, 0, 8000, 22050, 96000}) {
            assertThrows(IllegalArgumentException.class, () -> LelinkStreamSetup.audioRequest(rate, 5000, 5001));
        }
    }

    @Test public void teardownRequestSelectsOnlyOneMediaTypeWithoutForeignSessionIdentifiers() throws Exception {
        for (int type : new int[]{96, 97}) {
            byte[] request = LelinkStreamSetup.teardownRequest(type);
            assertEquals("bplist00", new String(request, 0, 8, StandardCharsets.US_ASCII));
            NSDictionary root = (NSDictionary) PropertyListParser.parse(request);
            assertEquals(1, root.count());
            NSArray streams = (NSArray) root.objectForKey("streams");
            assertEquals(1, streams.count());
            NSDictionary stream = (NSDictionary) streams.objectAtIndex(0);
            assertEquals(1, stream.count());
            assertEquals(type, number(stream, "type"));
            for (String key : new String[]{"uuid", "sessionID", "session-id", "deviceID", "device-id",
                    "encrypt-mode", "mst", "ast", "timing-port", "control-port", "data-port"}) {
                assertNull(root.objectForKey(key));
                assertNull(stream.objectForKey(key));
            }
        }
    }

    @Test public void teardownRejectsEveryOtherProtocolTypeAndIntegerBoundaries() {
        for (int type = 0; type <= 65535; type++) {
            if (type == 96 || type == 97) continue;
            int invalidType = type;
            assertThrows(IllegalArgumentException.class, () -> LelinkStreamSetup.teardownRequest(invalidType));
        }
        for (int type : new int[]{Integer.MIN_VALUE, -1, 65536, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> LelinkStreamSetup.teardownRequest(type));
        }
    }

    @Test public void parsesVideoPortsAndDoesNotConfuseOptionalExtensionsWithMediaPorts() throws Exception {
        var ports = video(integer("timing-port", 6000) + integer("event-port", 6001)
                + streams(videoFields() + integer("mouse-port", 6002)));
        assertEquals(new LelinkStreamSetup.VideoPorts(5000, 5001, 6000, 10000), ports);
    }

    @Test public void omittedTimingPortsRemainUnknownForSessionInheritance() throws Exception {
        assertEquals(0, video(streams(videoFields())).timingPort());
        assertEquals(0, audio(streams(audioFields())).timingPort());
    }

    @Test public void parsesAudioUdpPortsAndAcceptsPortBoundaries() throws Exception {
        var ports = audio(integer("event-port", 6000) + integer("timing-port", 65535)
                + streams(integer("type", 96) + integer("ast", 1)
                + integer("data-port", 1) + integer("control-port", 65535)));
        assertEquals(new LelinkStreamSetup.AudioPorts(1, 65535, 65535), ports);
    }

    @Test public void rejectsUnrequestedStreamTypesAndAudioTransports() {
        assertThrows(IOException.class, () -> video(streams(audioFields())));
        assertThrows(IOException.class, () -> audio(streams(videoFields())));
        for (int type : new int[]{0, 2, 3, 65535}) {
            assertThrows(IOException.class, () -> audio(streams(audioFields().replace(
                    integer("ast", 1), integer("ast", type)))));
        }
    }

    @Test public void requiresAllNegotiatedStreamFieldsAndExactlyOneStream() {
        for (String field : new String[]{integer("type", 97), integer("data-port", 5000)}) {
            assertThrows(IOException.class, () -> video(streams(videoFields().replace(field, ""))));
        }
        for (String field : new String[]{integer("type", 96), integer("ast", 1),
                integer("data-port", 5002), integer("control-port", 5003)}) {
            assertThrows(IOException.class, () -> audio(streams(audioFields().replace(field, ""))));
        }
        for (String fields : new String[]{"", "<key>streams</key><dict/>", "<key>streams</key><array/>",
                "<key>streams</key><array><dict>" + videoFields() + "</dict><dict>" + videoFields() + "</dict></array>"}) {
            assertThrows(IOException.class, () -> video(fields));
        }
    }

    @Test public void rejectsZeroInvalidOutOfRangeAndNonIntegerPorts() {
        for (String value : new String[]{"0", "-1", "+1", "65536", "999999999", "1.0", "1e2", "NaN", ""}) {
            String bad = "<key>data-port</key><integer>" + value + "</integer>";
            assertThrows(IOException.class, () -> video(streams(videoFields().replace(integer("data-port", 5000), bad))));
            assertThrows(IOException.class, () -> audio(streams(audioFields().replace(integer("data-port", 5002), bad))));
        }
        for (String value : new String[]{"<string>5000</string>", "<real>5000</real>", "<true/>",
                "<array><integer>5000</integer></array>", "<integer><integer>5000</integer></integer>"}) {
            assertThrows(IOException.class, () -> video(streams(videoFields().replace(
                    integer("data-port", 5000), "<key>data-port</key>" + value))));
        }
        assertThrows(IOException.class, () -> video(integer("timing-port", 0) + streams(videoFields())));
        assertThrows(IOException.class, () -> audio(integer("timing-port", 0) + streams(audioFields())));
    }

    @Test public void tcpDoesNotRequireUnusedUdpFieldsButStillValidatesThem() throws Exception {
        String required = integer("type", 97) + integer("data-port", 5000);
        assertEquals(new LelinkStreamSetup.VideoPorts(5000, 0, 0, 0), video(streams(required)));
        assertEquals(new LelinkStreamSetup.VideoPorts(5000, 0, 0, 0),
                video(streams(required + integer("udp-port", 0) + integer("max-seq-num", 0))));
        for (String field : new String[]{"udp-port", "max-seq-num"}) {
            assertThrows(IOException.class, () -> video(streams(required + integer(field, 65536))));
            assertThrows(IOException.class, () -> video(streams(required + integer(field, -1))));
            assertThrows(IOException.class, () -> video(streams(required + "<key>" + field + "</key><string>0</string>")));
        }
    }

    @Test public void rejectsDuplicateKeysIncludingIgnoredExtensionDictionaries() {
        assertThrows(IOException.class, () -> video(streams(videoFields()) + streams(videoFields())));
        assertThrows(IOException.class, () -> video(streams(videoFields() + integer("data-port", 5000))));
        assertThrows(IOException.class, () -> audio(streams(audioFields() + integer("ast", 1))));
        assertThrows(IOException.class, () -> video(streams(videoFields())
                + "<key>extension</key><dict>" + integer("future", 0) + integer("future", 1) + "</dict>"));
        assertThrows(IOException.class, () -> video(streams(videoFields())
                + "<key>future</key><string>a</string><key>future</key><string>b</string>"));
    }

    @Test public void acceptsAppleDoctypeAndUtf8BomWithoutFetchingExternalDtd() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://127.0.0.1:1/never-fetch.dtd\">"
                + plist(streams(videoFields()));
        assertEquals(5000, LelinkStreamSetup.parseVideo(bytes(xml)).dataPort());
        assertEquals(5000, LelinkStreamSetup.parseVideo(bytes("\ufeff" + xml)).dataPort());
    }

    @Test public void rejectsEntitiesAndInternalDtdDeclarations() {
        for (String declaration : new String[]{"<!ENTITY x '1'>", "<!ENTITY x SYSTEM 'file:///etc/passwd'>",
                "<!ENTITY % x SYSTEM 'http://127.0.0.1:1/never-fetch'> %x;", "<!ELEMENT plist ANY>"}) {
            String xml = "<!DOCTYPE plist [" + declaration + "]>" + plist(streams(videoFields()));
            assertThrows(IOException.class, () -> LelinkStreamSetup.parseVideo(bytes(xml)));
        }
        assertThrows(IOException.class, () -> video(streams(videoFields().replace("5000", "&missing;"))));
    }

    @Test public void rejectsMalformedRootKeysScalarsAndTrailingContent() {
        for (String xml : new String[]{"<root><dict>" + streams(videoFields()) + "</dict></root>",
                "<plist><dict/><dict/></plist>", "<plist><array/></plist>", plist(streams(videoFields()) + "extra"),
                plist(streams(videoFields()) + "<key>orphan</key>"),
                plist(streams(videoFields()) + "<string>key</string><integer>1</integer>"),
                plist(streams(videoFields()) + "<key><string>bad</string></key><integer>1</integer>"),
                plist(streams(videoFields()) + "<key>unknown</key><other/>"),
                plist(streams(videoFields())) + "extra", "<plist><dict>"}) {
            assertThrows(IOException.class, () -> LelinkStreamSetup.parseVideo(bytes(xml)));
        }
    }

    @Test public void rejectsOversizedEmptyNullAndMalformedUtf8Bodies() {
        for (byte[] body : new byte[][]{null, new byte[0], new byte[LelinkStreamSetup.MAX_BODY_SIZE + 1],
                {(byte) 0xc3, 0x28}, {0, '<', 'p'}, bytes("bplist00")}) {
            assertThrows(IOException.class, () -> LelinkStreamSetup.parseVideo(body));
            assertThrows(IOException.class, () -> LelinkStreamSetup.parseAudio(body));
        }
    }

    @Test public void acceptsExactBodySizeBoundAndRejectsOneMoreByte() throws Exception {
        String xml = plist(streams(videoFields()));
        String bounded = xml + " ".repeat(LelinkStreamSetup.MAX_BODY_SIZE - bytes(xml).length);
        assertEquals(5000, LelinkStreamSetup.parseVideo(bytes(bounded)).dataPort());
        assertThrows(IOException.class, () -> LelinkStreamSetup.parseVideo(bytes(bounded + " ")));
    }

    @Test public void ignoredExtensionsStillHaveDepthElementKeyAndPairLimits() {
        String deep = "<array>".repeat(32) + "</array>".repeat(32);
        String numerous = "<array>" + "<integer>0</integer>".repeat(512) + "</array>";
        for (String extension : new String[]{deep, numerous}) {
            assertThrows(IOException.class, () -> video(streams(videoFields()) + "<key>extension</key>" + extension));
        }
        assertThrows(IOException.class, () -> video(streams(videoFields())
                + "<key>" + "x".repeat(129) + "</key><integer>0</integer>"));
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < 64; i++) pairs.append(integer("field" + i, i));
        assertThrows(IOException.class, () -> video(streams(videoFields()) + pairs));
    }

    private static int number(NSDictionary dict, String key) { return ((NSNumber) dict.objectForKey(key)).intValue(); }
    private static LelinkStreamSetup.VideoPorts video(String fields) throws IOException {
        return LelinkStreamSetup.parseVideo(bytes(plist(fields)));
    }
    private static LelinkStreamSetup.AudioPorts audio(String fields) throws IOException {
        return LelinkStreamSetup.parseAudio(bytes(plist(fields)));
    }
    private static String videoFields() {
        return integer("type", 97) + integer("data-port", 5000) + integer("udp-port", 5001) + integer("max-seq-num", 10000);
    }
    private static String audioFields() {
        return integer("type", 96) + integer("ast", 1) + integer("data-port", 5002) + integer("control-port", 5003);
    }
    private static String streams(String fields) { return "<key>streams</key><array><dict>" + fields + "</dict></array>"; }
    private static String integer(String key, int value) { return "<key>" + key + "</key><integer>" + value + "</integer>"; }
    private static String plist(String fields) { return "<plist version=\"1.0\"><dict>" + fields + "</dict></plist>"; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
