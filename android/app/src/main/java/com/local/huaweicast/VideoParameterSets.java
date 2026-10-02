package com.local.huaweicast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Collects Annex-B codec headers even when MediaCodec delivers them separately. */
public final class VideoParameterSets {
    private final VideoCodec codec;
    private byte[] vps, sps, pps;
    public VideoParameterSets(VideoCodec codec) { this.codec = codec; }
    public void accept(byte[] data) {
        for (byte[] nal : splitAnnexB(data)) {
            int type = codec.nalType(nal);
            if (codec == VideoCodec.H265 && type == 32) vps = withStartCode(nal);
            if (type == (codec == VideoCodec.H265 ? 33 : 7)) sps = withStartCode(nal);
            if (type == (codec == VideoCodec.H265 ? 34 : 8)) pps = withStartCode(nal);
        }
    }
    public boolean complete() { return sps != null && pps != null && (codec == VideoCodec.H264 || vps != null); }
    public byte[] vps() { return vps; }
    public byte[] sps() { return sps; }
    public byte[] pps() { return pps; }
    public static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> nals = new ArrayList<>();
        int start = -1, i = 0;
        while (i + 3 <= data.length) {
            int prefix = 0;
            if (data[i] == 0 && data[i+1] == 0) {
                if (data[i+2] == 1) prefix = 3;
                else if (i+4 <= data.length && data[i+2] == 0 && data[i+3] == 1) prefix = 4;
            }
            if (prefix > 0) {
                if (start >= 0 && start < i) nals.add(Arrays.copyOfRange(data, start, i));
                i += prefix; start = i;
            } else i++;
        }
        if (start >= 0 && start < data.length) nals.add(Arrays.copyOfRange(data, start, data.length));
        return nals;
    }
    private static byte[] withStartCode(byte[] nal) {
        byte[] result = new byte[nal.length + 4]; result[3] = 1;
        System.arraycopy(nal, 0, result, 4, nal.length); return result;
    }
}
