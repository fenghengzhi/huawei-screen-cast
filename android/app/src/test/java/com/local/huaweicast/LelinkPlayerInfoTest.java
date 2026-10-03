package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class LelinkPlayerInfoTest {
    @Test public void parsesFreePairingModesAndOptionalScalarCapabilities() throws Exception {
        LelinkPlayerInfo info = parse(modes(1, 0) + integer("etv", 0) + integer("mst", 2) + integer("ast", 3)
                + "<key>displays</key><array><dict><key>width</key><integer>1920</integer></dict></array>");
        assertTrue(info.advertisesFreePairing());
        assertEquals(1, info.htv());
        assertEquals(0, info.atv());
        assertEquals(0, info.etv().getAsInt());
        assertEquals(2, info.mst().getAsInt());
        assertEquals(3, info.ast().getAsInt());
    }

    @Test public void requiredModesAreNotInferredFromPasswordPinOrUnknownValues() throws Exception {
        for (int[] mode : new int[][]{{1, 1}, {1, 2}, {0, 0}, {2, 0}, {65535, 65535}, {0, 1}}) {
            LelinkPlayerInfo info = parse(modes(mode[0], mode[1]));
            assertFalse(info.advertisesFreePairing());
            assertEquals(mode[0], info.htv());
            assertEquals(mode[1], info.atv());
        }
    }

    @Test public void acceptsBoundedIntegralScalarsWithoutFractionalCoercion() throws Exception {
        LelinkPlayerInfo info = parse("<key>htv</key><real>1.0000</real><key>atv</key><string>0</string>"
                + "<key>mst</key><integer>65535</integer>");
        assertTrue(info.advertisesFreePairing());
        assertEquals(65535, info.mst().getAsInt());
    }

    @Test public void missingRequiredModesCannotAdvertiseFreePairing() {
        for (String fields : new String[]{"", integer("htv", 1), integer("atv", 0),
                "<key>nested</key><dict>" + modes(1, 0) + "</dict>"}) {
            assertThrows(IOException.class, () -> parse(fields));
        }
    }

    @Test public void optionalMissingOrStructuredCapabilitiesRemainUnknown() throws Exception {
        LelinkPlayerInfo missing = parse(modes(1, 0));
        assertFalse(missing.etv().isPresent());
        assertFalse(missing.mst().isPresent());
        assertFalse(missing.ast().isPresent());
        LelinkPlayerInfo structured = parse(modes(1, 0) + "<key>mst</key><dict><key>value</key><integer>1</integer></dict>"
                + "<key>ast</key><array><integer>2</integer></array><key>etv</key><true/>");
        assertTrue(structured.advertisesFreePairing());
        assertFalse(structured.etv().isPresent());
        assertFalse(structured.mst().isPresent());
        assertFalse(structured.ast().isPresent());
    }

    @Test public void requiredModesRejectStructuredAndBooleanValues() {
        for (String type : new String[]{"<dict/>", "<array/>", "<true/>", "<false/>", "<data>MQ==</data>",
                "<integer><integer>1</integer></integer>"}) {
            assertThrows(IOException.class, () -> parse("<key>htv</key>" + type + integer("atv", 0)));
            assertThrows(IOException.class, () -> parse(integer("htv", 1) + "<key>atv</key>" + type));
        }
    }

    @Test public void duplicatesOfEveryKnownFieldAreRejectedEvenWhenFirstIsStructured() {
        for (String key : new String[]{"htv", "atv", "etv", "mst", "ast"}) {
            assertThrows(IOException.class, () -> parse(modes(1, 0) + integer(key, 1) + integer(key, 1)));
        }
        assertThrows(IOException.class, () -> parse(modes(1, 0) + "<key>mst</key><dict/>" + integer("mst", 1)));
    }

    @Test public void invalidOrOutOfRangeScalarsAreRejected() {
        for (String value : new String[]{"", "-1", "+1", "65536", "9999999999999999", "1.5", "NaN", "Infinity",
                "1e0", "0x1", "true", "1 0", "1.000000000"}) {
            for (String type : new String[]{"integer", "real", "string"}) {
                String scalar = "<" + type + ">" + value + "</" + type + ">";
                assertThrows(IOException.class, () -> parse("<key>htv</key>" + scalar + integer("atv", 0)));
                assertThrows(IOException.class, () -> parse(modes(1, 0) + "<key>mst</key>" + scalar));
            }
        }
        assertThrows(IOException.class, () -> parse("<key>htv</key><integer>1.0</integer>" + integer("atv", 0)));
    }

    @Test public void ignoresUnknownFieldsWithoutReadingNestedModeNames() throws Exception {
        LelinkPlayerInfo info = parse(modes(2, 3) + "<key>extension</key><dict>" + modes(1, 0)
                + "</dict><key>future</key><string>not-a-number</string>");
        assertFalse(info.advertisesFreePairing());
        assertEquals(2, info.htv());
        assertEquals(3, info.atv());
    }

    @Test public void acceptsAppleDoctypeWithoutLoadingItsExternalDtd() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://127.0.0.1:1/never-fetch.dtd\">"
                + plist(modes(1, 0));
        assertTrue(LelinkPlayerInfo.parse(bytes(xml)).advertisesFreePairing());
        assertTrue(LelinkPlayerInfo.parse(bytes("\ufeff" + xml)).advertisesFreePairing());
    }

    @Test public void entityAndInternalDtdDeclarationsAreRejected() {
        for (String declaration : new String[]{"<!ENTITY x '1'>", "<!ENTITY x SYSTEM 'file:///etc/passwd'>",
                "<!ENTITY x SYSTEM 'http://127.0.0.1:1/never-fetch'>",
                "<!ENTITY % x SYSTEM 'http://127.0.0.1:1/never-fetch'> %x;",
                "<!ELEMENT plist ANY>"}) {
            String xml = "<!DOCTYPE plist [" + declaration + "]>" + plist(modes(1, 0));
            assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(bytes(xml)));
        }
    }

    @Test public void undefinedEntityReferencesCannotBecomeAnImplicitZero() {
        String xml = plist(integer("htv", 1) + "<key>atv</key><integer>&missing;</integer>");
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(bytes(xml)));
    }

    @Test public void rejectsMalformedRootDictionaryKeysAndTrailingText() {
        for (String xml : new String[]{"<root><dict>" + modes(1, 0) + "</dict></root>",
                "<plist><dict/><dict/></plist>", "<plist><array/></plist>",
                plist(modes(1, 0) + "<key>orphan</key>"),
                plist(modes(1, 0) + "<string>key</string><integer>1</integer>"),
                plist("<key><string>htv</string></key><integer>1</integer>" + integer("atv", 0)),
                plist(modes(1, 0) + "unexpected"), "<plist><dict>" + modes(1, 0),
                plist(modes(1, 0)) + "extra"}) {
            assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(bytes(xml)));
        }
    }

    @Test public void rejectsOversizedEmptyNullAndNonUtf8Bodies() {
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(null));
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(new byte[0]));
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(new byte[LelinkPlayerInfo.MAX_BODY_SIZE + 1]));
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(new byte[]{0, '<', 'p'}));
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(bytes("bplist00not-xml")));
    }

    @Test public void acceptsExactlyBoundedBodyAndRejectsAdditionalByte() throws Exception {
        String xml = plist(modes(1, 0));
        String bounded = xml + " ".repeat(LelinkPlayerInfo.MAX_BODY_SIZE - bytes(xml).length);
        assertTrue(LelinkPlayerInfo.parse(bytes(bounded)).advertisesFreePairing());
        assertThrows(IOException.class, () -> LelinkPlayerInfo.parse(bytes(bounded + " ")));
    }

    @Test public void ignoredExtensionsStillHaveDepthAndElementLimits() {
        String deep = "<array>".repeat(LelinkPlayerInfo.MAX_DEPTH) + "</array>".repeat(LelinkPlayerInfo.MAX_DEPTH);
        assertThrows(IOException.class, () -> parse(modes(1, 0) + "<key>extension</key>" + deep));
        String numerous = "<array>" + "<integer>0</integer>".repeat(LelinkPlayerInfo.MAX_ELEMENTS) + "</array>";
        assertThrows(IOException.class, () -> parse(modes(1, 0) + "<key>extension</key>" + numerous));
    }

    @Test public void rejectsTooManyTopLevelPairsAndOversizedKeys() {
        String pairs = "<key>extension</key><integer>0</integer>".repeat(63);
        assertThrows(IOException.class, () -> parse(modes(1, 0) + pairs));
        assertThrows(IOException.class, () -> parse(modes(1, 0) + "<key>" + "x".repeat(129) + "</key><integer>0</integer>"));
    }

    private static LelinkPlayerInfo parse(String fields) throws IOException { return LelinkPlayerInfo.parse(bytes(plist(fields))); }
    private static String modes(int htv, int atv) { return integer("htv", htv) + integer("atv", atv); }
    private static String integer(String key, int value) { return "<key>" + key + "</key><integer>" + value + "</integer>"; }
    private static String plist(String fields) { return "<plist version=\"1.0\"><dict>" + fields + "</dict></plist>"; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
