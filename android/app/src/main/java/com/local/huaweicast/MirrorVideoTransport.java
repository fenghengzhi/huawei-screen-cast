package com.local.huaweicast;

/** Video transport shared by the native Lelink and legacy compatibility sessions. */
public interface MirrorVideoTransport extends AutoCloseable {
    void connect(LelinkEndpoint endpoint, LegacyMirrorClient.Binder binder) throws Exception;
    void configure(byte[] sps, byte[] pps);
    void configureHevc(byte[] vps, byte[] sps, byte[] pps);
    void resize(int width, int height);
    void offer(byte[] data, long monotonicUs, boolean key);
    long sentFrames();
    @Override void close();
}
