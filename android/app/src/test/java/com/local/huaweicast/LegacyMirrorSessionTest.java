package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyMirrorSessionTest {
    @Test public void captureTimestampsUseOneRelativeEpoch() {
        LegacyMirrorSession session = new LegacyMirrorSession(42, 7, 9_000_000_000L);
        assertEquals(125, session.relativeUs(9_000_125L));
        assertEquals(0, session.relativeUs(8_999_999L));
    }
    @Test public void generatedIdentityIsBoundedAndLocal() {
        LegacyMirrorSession session = LegacyMirrorSession.create();
        assertTrue(session.deviceId() >= 0 && session.deviceId() <= 0xffffffffffffL);
        assertTrue((session.deviceId() & 0x020000000000L) != 0);
        assertTrue(session.sessionId() >= 0 && session.sessionId() <= 0x7fffffffL);
        assertTrue(session.nowUs() >= 0);
    }
    @Test public void rejectsOutOfRangeIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyMirrorSession(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LegacyMirrorSession(0, 0x80000000L, 0));
    }
}
