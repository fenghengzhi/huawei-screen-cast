package com.local.huaweicast;

/** Supported plain AAC-ELD AudioSpecificConfig, with sample counts per channel. */
public record AacEldConfig(int sampleRate, int channels, int samplesPerFrame) {
    private static final int[] SAMPLE_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    public AacEldConfig {
        if ((sampleRate != 44100 && sampleRate != 48000) || channels != 2
                || (samplesPerFrame != 512 && samplesPerFrame != 480)) {
            throw new IllegalArgumentException("Unsupported AAC-ELD audio configuration");
        }
    }

    public static AacEldConfig parse(byte[] csd) {
        if (csd == null || csd.length == 0 || csd.length > 64) {
            throw new IllegalArgumentException("Invalid AAC AudioSpecificConfig length");
        }
        Bits bits = new Bits(csd.clone());
        int objectType = bits.read(5);
        if (objectType == 31) objectType = 32 + bits.read(6);
        if (objectType != 39) throw new IllegalArgumentException("Expected AAC-ELD object type 39");
        int frequencyIndex = bits.read(4);
        int rate;
        if (frequencyIndex == 15) rate = bits.read(24);
        else if (frequencyIndex < SAMPLE_RATES.length) rate = SAMPLE_RATES[frequencyIndex];
        else throw new IllegalArgumentException("Reserved AAC sampling frequency index");
        int channels = bits.read(4);

        // ISO 14496-3 ELD layout, cross-checked against AOSP FDK's EldSpecificConfig_Parse:
        // frameLengthFlag=0 selects 512 samples; 1 selects 480, unlike AAC-LC's 1024/960.
        int samples = bits.read(1) == 0 ? 512 : 480;
        if (bits.read(3) != 0) throw new IllegalArgumentException("AAC-ELD resilience tools are unsupported");
        if (bits.read(1) != 0) throw new IllegalArgumentException("AAC-ELD SBR is unsupported");
        if (bits.read(4) != 0) throw new IllegalArgumentException("AAC-ELD extensions are unsupported");
        if (bits.read(2) != 0) throw new IllegalArgumentException("AAC-ELD error protection is unsupported");
        while (bits.remaining() > 0) {
            if (bits.read(1) != 0) throw new IllegalArgumentException("Unexpected AAC-ELD trailing extension");
        }
        return new AacEldConfig(rate, channels, samples);
    }

    private static final class Bits {
        private final byte[] data;
        private int offset;
        private Bits(byte[] data) { this.data = data; }
        private int remaining() { return data.length * 8 - offset; }
        private int read(int count) {
            if (count < 1 || count > 24 || remaining() < count) {
                throw new IllegalArgumentException("Truncated AAC AudioSpecificConfig");
            }
            int value = 0;
            for (int i = 0; i < count; i++, offset++) {
                value = (value << 1) | ((data[offset / 8] >>> (7 - offset % 8)) & 1);
            }
            return value;
        }
    }
}
