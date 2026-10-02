package com.local.huaweicast;

public enum VideoCodec {
    H264("h264", "H.264", "video/avc", 0x1b),
    H265("h265", "H.265", "video/hevc", 0x24);

    private final String id, label, mime;
    private final int streamType;
    VideoCodec(String id, String label, String mime, int streamType) { this.id = id; this.label = label; this.mime = mime; this.streamType = streamType; }
    public String id() { return id; }
    public String label() { return label; }
    public String mime() { return mime; }
    public int streamType() { return streamType; }
    public int nalType(byte[] nal) { if (nal.length < (this == H265 ? 2 : 1)) return -1; return this == H265 ? (nal[0] & 0x7e) >> 1 : nal[0] & 0x1f; }
    public boolean isVideoNal(byte[] nal) { int type = nalType(nal); return this == H265 ? type >= 0 && type <= 31 : type >= 1 && type <= 5; }
    public boolean isKeyNal(byte[] nal) { int type = nalType(nal); return this == H265 ? type >= 16 && type <= 21 : type == 5; }
    public static VideoCodec fromId(String id) { return H265.id.equals(id) ? H265 : H264; }
}
