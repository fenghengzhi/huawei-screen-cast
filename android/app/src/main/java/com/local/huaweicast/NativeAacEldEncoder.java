package com.local.huaweicast;

import java.util.Objects;

/** Fixed 480-sample stereo AAC-ELD encoder for the legacy mirror audio transport. */
public final class NativeAacEldEncoder implements AutoCloseable {
    private static boolean libraryLoaded;
    private long handle;

    public NativeAacEldEncoder(int sampleRate) {
        if (sampleRate != 44100 && sampleRate != 48000) {
            throw new IllegalArgumentException("AAC-ELD requires 44100 or 48000 Hz");
        }
        loadLibrary();
        try {
            handle = nativeCreate(sampleRate);
        } catch (LinkageError failure) {
            throw new IllegalStateException("AAC-ELD encoder is unavailable on this device", failure);
        }
        if (handle == 0) throw new IllegalStateException("Unable to create AAC-ELD encoder");
    }

    private static synchronized void loadLibrary() {
        if (libraryLoaded) return;
        try {
            System.loadLibrary("huaweicast_aaceld");
            libraryLoaded = true;
        } catch (LinkageError failure) {
            throw new IllegalStateException("AAC-ELD encoder is unavailable on this device", failure);
        }
    }

    public synchronized int samplesPerFrame() {
        return nativeSamplesPerFrame(requireOpen());
    }

    /** Codec delay in samples per channel, excluding time spent filling an input frame. */
    public synchronized int delaySamples() {
        return nativeDelaySamples(requireOpen());
    }

    public synchronized byte[] configuration() {
        return nativeConfiguration(requireOpen());
    }

    /** Encodes exactly one interleaved PCM16LE stereo frame, or returns null without output. */
    public synchronized byte[] encode(byte[] pcm, int size) {
        long openHandle = requireOpen();
        Objects.requireNonNull(pcm, "pcm");
        if (size != 480 * 2 * 2 || size > pcm.length) {
            throw new IllegalArgumentException("AAC-ELD requires exactly 1920 PCM bytes per frame");
        }
        return nativeEncode(openHandle, pcm, size);
    }

    @Override
    public synchronized void close() {
        if (handle == 0) return;
        long closingHandle = handle;
        handle = 0;
        nativeClose(closingHandle);
    }

    private long requireOpen() {
        if (handle == 0) throw new IllegalStateException("AAC-ELD encoder is closed");
        return handle;
    }

    // All operations on a published native handle share this instance's monitor.
    private static native long nativeCreate(int sampleRate);
    private static native int nativeSamplesPerFrame(long handle);
    private static native int nativeDelaySamples(long handle);
    private static native byte[] nativeConfiguration(long handle);
    private static native byte[] nativeEncode(long handle, byte[] pcm, int size);
    private static native void nativeClose(long handle);
}
