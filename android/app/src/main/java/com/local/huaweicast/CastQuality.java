package com.local.huaweicast;

/** Validated settings shared by the editor and the foreground service. */
public record CastQuality(int height, int fps, int kbps) {
    public static final int[] HEIGHTS = {360, 540, 720, 1080};
    public static final int[] FRAME_RATES = {15, 20, 25, 30, 60};
    public static final CastQuality DEFAULT = new CastQuality(540, 20, 1200);
    public CastQuality {
        if (!contains(HEIGHTS, height)) height = 540;
        if (!contains(FRAME_RATES, fps)) fps = 20;
        kbps = Math.max(500, Math.min(12000, kbps));
        kbps = Math.round(kbps / 100f) * 100;
    }
    private static boolean contains(int[] options, int value) { for (int option : options) if (option == value) return true; return false; }
    public int width() { return height * 16 / 9; }
    public int bitRate() { return kbps * 1000; }
    public String summary() { return height + "p · " + fps + " fps · " + String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000.0); }
}
