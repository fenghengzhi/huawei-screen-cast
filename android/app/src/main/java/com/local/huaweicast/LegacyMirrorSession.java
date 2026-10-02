package com.local.huaweicast;

import java.security.SecureRandom;

/** Ephemeral identity and monotonic clock shared by a single audio/video session. */
public record LegacyMirrorSession(long deviceId, long sessionId, long epochNs) {
    public LegacyMirrorSession {
        if (deviceId < 0 || deviceId > 0xffffffffffffL || sessionId < 0 || sessionId > 0x7fffffffL) {
            throw new IllegalArgumentException("Invalid mirror session identity");
        }
    }
    public static LegacyMirrorSession create() {
        SecureRandom random = new SecureRandom();
        return new LegacyMirrorSession((random.nextLong() & 0x0000ffffffffffffL) | 0x020000000000L,
                random.nextInt() & 0x7fffffffL, System.nanoTime());
    }
    public long relativeUs(long monotonicUs) { return Math.max(0, monotonicUs - epochNs / 1000); }
    public long nowUs() { return Math.max(0, (System.nanoTime() - epochNs) / 1000); }
}
