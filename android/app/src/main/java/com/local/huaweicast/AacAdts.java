package com.local.huaweicast;

/** AAC-LC, 48 kHz, stereo framing for MPEG-TS. */
public final class AacAdts {
    private AacAdts() {}
    public static byte[] wrap(byte[] payload) {
        int length = payload.length + 7;
        if (length > 8191) throw new IllegalArgumentException("AAC frame too large");
        byte[] result = new byte[length];
        result[0] = (byte) 0xff; result[1] = (byte) 0xf1;
        result[2] = (byte) ((1 << 6) | (3 << 2));
        result[3] = (byte) ((2 << 6) | (length >> 11));
        result[4] = (byte) (length >> 3);
        result[5] = (byte) (((length & 7) << 5) | 0x1f);
        result[6] = (byte) 0xfc;
        System.arraycopy(payload, 0, result, 7, payload.length);
        return result;
    }
}
