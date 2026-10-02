package com.local.huaweicast;
import org.junit.Test;
import static org.junit.Assert.*;

public class CastQualityTest {
    @Test public void supportsAllResolutionAndRateCombinations() {
        for (int height : CastQuality.HEIGHTS) for (int fps : CastQuality.FRAME_RATES) {
            CastQuality value = new CastQuality(height, fps, 3200);
            assertEquals(height, value.height()); assertEquals(fps, value.fps()); assertEquals(height*16/9, value.width()); assertEquals(3200000, value.bitRate());
        }
    }
    @Test public void rejectsUnsupportedParametersAndBoundsBitrate() {
        assertEquals(new CastQuality(540,20,500), new CastQuality(4320, 240, Integer.MIN_VALUE));
        assertEquals(12000, new CastQuality(720,30,Integer.MAX_VALUE).kbps());
        assertEquals(1300, new CastQuality(720,30,1259).kbps());
        assertEquals("540p · 20 fps · 1.2 Mbps", CastQuality.DEFAULT.summary());
    }
}
