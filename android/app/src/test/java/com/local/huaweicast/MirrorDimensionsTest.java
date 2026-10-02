package com.local.huaweicast;

import org.junit.Test;

import static org.junit.Assert.*;

public class MirrorDimensionsTest {
    @Test public void fitsHuaweiPortraitWithoutLandscapeLetterboxing() {
        assertEquals(new MirrorDimensions(464, 960), MirrorDimensions.fit(1152, 2376, 540, 2, 2));
        assertEquals(new MirrorDimensions(464, 960), MirrorDimensions.fit(1152, 2376, 540, 16, 16));
    }

    @Test public void preservesRotationSymmetryWithSymmetricAlignment() {
        for (int quality : CastQuality.HEIGHTS) {
            for (int alignment : new int[]{1, 2, 8, 16}) {
                MirrorDimensions portrait = MirrorDimensions.fit(1152, 2376, quality, alignment, alignment);
                MirrorDimensions landscape = MirrorDimensions.fit(2376, 1152, quality, alignment, alignment);
                assertEquals(portrait.width(), landscape.height());
                assertEquals(portrait.height(), landscape.width());
            }
        }
    }

    @Test public void keepsSixteenByNineInsideQualityBounds() {
        assertEquals(new MirrorDimensions(960, 540), MirrorDimensions.fit(1920, 1080, 540, 2, 2));
        assertEquals(new MirrorDimensions(540, 960), MirrorDimensions.fit(1080, 1920, 540, 2, 2));
        assertEquals(new MirrorDimensions(960, 528), MirrorDimensions.fit(1920, 1080, 540, 16, 16));
    }

    @Test public void scalesUltrawideAndSquareSourcesWithoutCropping() {
        assertEquals(new MirrorDimensions(960, 270), MirrorDimensions.fit(3840, 1080, 540, 2, 2));
        assertEquals(new MirrorDimensions(270, 960), MirrorDimensions.fit(1080, 3840, 540, 2, 2));
        assertEquals(new MirrorDimensions(540, 540), MirrorDimensions.fit(2000, 2000, 540, 2, 2));
    }

    @Test public void doesNotUpscaleSmallSources() {
        assertEquals(new MirrorDimensions(320, 240), MirrorDimensions.fit(320, 240, 1080, 2, 2));
        assertEquals(new MirrorDimensions(240, 320), MirrorDimensions.fit(240, 320, 1080, 2, 2));
        assertEquals(new MirrorDimensions(320, 240), MirrorDimensions.fit(321, 241, 1080, 2, 2));
    }

    @Test public void alignsEachAxisIndependently() {
        assertEquals(new MirrorDimensions(464, 960), MirrorDimensions.fit(1152, 2376, 540, 16, 32));
        assertEquals(new MirrorDimensions(450, 952), MirrorDimensions.fit(1152, 2376, 540, 30, 17));
        assertEquals(new MirrorDimensions(960, 464), MirrorDimensions.fit(2376, 1152, 540, 32, 16));
    }

    @Test public void acceptsTinySourcesWhenAlignmentFits() {
        assertEquals(new MirrorDimensions(1, 1), MirrorDimensions.fit(1, 1, 1, 1, 1));
        assertEquals(new MirrorDimensions(2, 4), MirrorDimensions.fit(3, 5, 540, 2, 2));
    }

    @Test public void rejectsBoundsThatCannotAccommodateAlignment() {
        assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1, 100, 540, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(100, 1, 540, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1152, 2376, 540, 512, 2));
        assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(2376, 1152, 540, 2, 512));
        assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1000, 1, 1, 1, 1));
    }

    @Test public void rejectsNonPositiveInputsAndDimensions() {
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(invalid, 1080, 540, 2, 2));
            assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1920, invalid, 540, 2, 2));
            assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1920, 1080, invalid, 2, 2));
            assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1920, 1080, 540, invalid, 2));
            assertThrows(IllegalArgumentException.class, () -> MirrorDimensions.fit(1920, 1080, 540, 2, invalid));
            assertThrows(IllegalArgumentException.class, () -> new MirrorDimensions(invalid, 1080));
            assertThrows(IllegalArgumentException.class, () -> new MirrorDimensions(1920, invalid));
        }
    }

    @Test public void usesWideArithmeticForExtremeInputBounds() {
        int max = Integer.MAX_VALUE;
        assertEquals(new MirrorDimensions(max, max), MirrorDimensions.fit(max, max, max, 1, 1));
        assertEquals(new MirrorDimensions(max, 1), MirrorDimensions.fit(max, 1, max, 1, 1));
        assertEquals(new MirrorDimensions(1, max), MirrorDimensions.fit(1, max, max, 1, 1));
        assertEquals(new MirrorDimensions(540, 540), MirrorDimensions.fit(max, max, 540, 2, 2));
    }

    @Test public void remainsWithinSourceAndQualityForRepresentativeScreens() {
        for (int[] source : new int[][]{{1152, 2376}, {1920, 1080}, {1080, 1920},
                {2560, 1080}, {720, 720}, {333, 777}}) {
            for (int quality : CastQuality.HEIGHTS) {
                for (int alignment : new int[]{1, 2, 8, 16}) {
                    MirrorDimensions size = MirrorDimensions.fit(source[0], source[1], quality, alignment, alignment);
                    assertTrue(size.width() <= source[0]);
                    assertTrue(size.height() <= source[1]);
                    assertTrue(Math.max(size.width(), size.height()) <= quality * 16 / 9);
                    assertTrue(Math.min(size.width(), size.height()) <= quality);
                    assertEquals(0, size.width() % alignment);
                    assertEquals(0, size.height() % alignment);
                    double scale = Math.min(1.0, Math.min(
                            quality / (double) Math.min(source[0], source[1]),
                            (quality * 16 / 9) / (double) Math.max(source[0], source[1])));
                    assertTrue(source[0] * scale - size.width() < alignment + 0.000001);
                    assertTrue(source[1] * scale - size.height() < alignment + 0.000001);
                }
            }
        }
    }
}
