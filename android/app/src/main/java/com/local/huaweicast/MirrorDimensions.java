package com.local.huaweicast;

/** Capture dimensions that preserve the source aspect ratio up to encoder alignment. */
public record MirrorDimensions(int width, int height) {
    public MirrorDimensions {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Capture dimensions must be positive");
        }
    }

    public static MirrorDimensions fit(int sourceWidth, int sourceHeight, int qualityHeight,
                                       int widthAlignment, int heightAlignment) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || qualityHeight <= 0
                || widthAlignment <= 0 || heightAlignment <= 0) {
            throw new IllegalArgumentException("Source, quality and alignments must be positive");
        }
        long longEdge = qualityHeight * 16L / 9;
        long maxWidth = sourceWidth >= sourceHeight ? longEdge : qualityHeight;
        long maxHeight = sourceWidth >= sourceHeight ? qualityHeight : longEdge;

        // Keep the scale as a rational to avoid floating-point rounding at alignment boundaries.
        long numerator = 1;
        long denominator = 1;
        if (maxWidth < sourceWidth) {
            numerator = maxWidth;
            denominator = sourceWidth;
        }
        if (maxHeight * denominator < sourceHeight * numerator) {
            numerator = maxHeight;
            denominator = sourceHeight;
        }
        int width = (int) (sourceWidth * numerator / denominator);
        int height = (int) (sourceHeight * numerator / denominator);
        width -= width % widthAlignment;
        height -= height % heightAlignment;
        if (width == 0 || height == 0) {
            throw new IllegalArgumentException("Capture bounds cannot accommodate encoder alignment");
        }
        return new MirrorDimensions(width, height);
    }
}
