package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class AacEldConfigTest {
    private static byte[] hex(String source) {
        byte[] bytes = new byte[source.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(source.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    private static void rejects(byte[] bytes) {
        try { AacEldConfig.parse(bytes); fail("Expected unsupported or malformed ASC to be rejected"); }
        catch (IllegalArgumentException expected) {}
    }
    private static byte[] explicitRate(int rate) {
        String frequency = Integer.toBinaryString(rate);
        String bits = "11111" + "000111" + "1111" + "0".repeat(24 - frequency.length()) + frequency
                + "0010" + "0" + "000" + "0" + "0000" + "00";
        byte[] bytes = new byte[(bits.length() + 7) / 8];
        for (int i = 0; i < bits.length(); i++) if (bits.charAt(i) == '1') bytes[i / 8] |= (byte) (1 << (7 - i % 8));
        return bytes;
    }

    @Test public void parsesBothDeviceVerifiedEldConfigurations() {
        assertEquals(new AacEldConfig(44100, 2, 512), AacEldConfig.parse(hex("f8e84000")));
        assertEquals(new AacEldConfig(48000, 2, 512), AacEldConfig.parse(hex("f8e64000")));
    }

    @Test public void readsEld480FrameLengthFlagNotLc960Convention() {
        assertEquals(new AacEldConfig(44100, 2, 480), AacEldConfig.parse(hex("f8e85000")));
        assertEquals(new AacEldConfig(48000, 2, 480), AacEldConfig.parse(hex("f8e65000")));
    }

    @Test public void supportsExplicitFrequencySyntaxWithSameSupportedRates() {
        assertEquals(new AacEldConfig(44100, 2, 512), AacEldConfig.parse(explicitRate(44100)));
        assertEquals(new AacEldConfig(48000, 2, 512), AacEldConfig.parse(explicitRate(48000)));
        rejects(explicitRate(32000));
        rejects(explicitRate(0));
        rejects(Arrays.copyOf(explicitRate(48000), 4));
    }

    @Test public void rejectsLcAndOtherAudioObjectTypes() {
        rejects(hex("1210"));
        rejects(hex("1190"));
        rejects(hex("f8c84000"));
        rejects(hex("f9084000"));
    }

    @Test public void rejectsTruncatedAndOversizedInput() {
        byte[] valid = hex("f8e84000");
        rejects(null);
        for (int size = 0; size < valid.length; size++) rejects(Arrays.copyOf(valid, size));
        rejects(new byte[65]);
        rejects(hex("f8"));
    }

    @Test public void rejectsUnsupportedRatesChannelsAndReservedFrequencyIndices() {
        rejects(hex("f8ea4000"));
        rejects(hex("f8fa4000"));
        rejects(hex("f8fc4000"));
        rejects(hex("f8e82000"));
        rejects(hex("f8e80000"));
        rejects(hex("f8e86000"));
    }

    @Test public void rejectsConfigurationsWhoseEffectiveFrameCountWouldNeedMoreParsing() {
        rejects(hex("f8e84800"));
        rejects(hex("f8e84400"));
        rejects(hex("f8e84200"));
        rejects(hex("f8e84100"));
        rejects(hex("f8e84010"));
        rejects(hex("f8e84004"));
        rejects(hex("f8e84001"));
        rejects(hex("f8e840002b70"));
        assertEquals(new AacEldConfig(44100, 2, 512), AacEldConfig.parse(hex("f8e8400000")));
    }

    @Test public void recordCannotRepresentLcFrameCountsOrUnsupportedStreams() {
        for (int samples : new int[]{0, 960, 1024, 2048}) {
            try { new AacEldConfig(44100, 2, samples); fail(); }
            catch (IllegalArgumentException expected) {}
        }
        try { new AacEldConfig(32000, 2, 512); fail(); }
        catch (IllegalArgumentException expected) {}
        try { new AacEldConfig(48000, 1, 512); fail(); }
        catch (IllegalArgumentException expected) {}
    }
}
