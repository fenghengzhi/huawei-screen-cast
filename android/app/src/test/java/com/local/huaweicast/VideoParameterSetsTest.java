package com.local.huaweicast;
import org.junit.Test;
import static org.junit.Assert.*;

public class VideoParameterSetsTest {
    @Test public void hevcNeedsAllThreeHeadersAndAcceptsAnyOrder() {
        VideoParameterSets sets = new VideoParameterSets(VideoCodec.H265);
        sets.accept(new byte[]{0,0,1,0x44,1,12});
        sets.accept(new byte[]{0,0,0,1,0x42,1,11});
        assertFalse(sets.complete());
        sets.accept(new byte[]{0,0,0,1,0x40,1,10});
        assertTrue(sets.complete());
        assertArrayEquals(new byte[]{0,0,0,1,0x40,1,10}, sets.vps());
        assertArrayEquals(new byte[]{0,0,0,1,0x42,1,11}, sets.sps());
        assertArrayEquals(new byte[]{0,0,0,1,0x44,1,12}, sets.pps());
    }
    @Test public void avcUsesNalTypesRatherThanPosition() {
        VideoParameterSets sets = new VideoParameterSets(VideoCodec.H264);
        sets.accept(new byte[]{0,0,0,1,9,16,0,0,1,8,22,0,0,0,1,7,33});
        assertTrue(sets.complete()); assertNull(sets.vps());
        assertArrayEquals(new byte[]{0,0,0,1,7,33}, sets.sps());
        assertArrayEquals(new byte[]{0,0,0,1,8,22}, sets.pps());
    }
    @Test public void emptyOrTruncatedHeadersDoNotBecomeReady() {
        VideoParameterSets sets = new VideoParameterSets(VideoCodec.H265);
        sets.accept(new byte[]{}); sets.accept(new byte[]{0,0,1}); sets.accept(new byte[]{0,0,1,0x42});
        assertFalse(sets.complete());
    }
    @Test public void mixedConfigAndVideoCanBeIdentifiedWithoutDroppingKeyframe() {
        var nals = VideoParameterSets.splitAnnexB(new byte[]{0,0,1,0x40,1,4,0,0,0,1,0x26,1,9});
        assertFalse(VideoCodec.H265.isVideoNal(nals.get(0)));
        assertTrue(VideoCodec.H265.isVideoNal(nals.get(1)));
        assertTrue(VideoCodec.H265.isKeyNal(nals.get(1)));
        assertTrue(VideoCodec.H264.isKeyNal(new byte[]{0x65,1}));
    }
}
