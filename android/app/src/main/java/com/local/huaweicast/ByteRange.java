package com.local.huaweicast;

public record ByteRange(long start, long end, boolean partial) {
    public long length() { return end - start + 1; }
    public static ByteRange parse(String header, long size) {
        if (size <= 0) throw new IllegalArgumentException("空文件");
        if (header == null || header.isEmpty()) return new ByteRange(0, size - 1, false);
        if (!header.startsWith("bytes=") || header.contains(",")) throw new IllegalArgumentException("无效范围");
        String[] parts = header.substring(6).split("-", -1);
        if (parts.length != 2) throw new IllegalArgumentException("无效范围");
        long start, end;
        if (parts[0].isEmpty()) {
            long suffix = Long.parseLong(parts[1]);
            if (suffix <= 0) throw new IllegalArgumentException("无效范围");
            start = Math.max(0, size - suffix); end = size - 1;
        } else {
            start = Long.parseLong(parts[0]); end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
        }
        if (start < 0 || start >= size || end < start) throw new IllegalArgumentException("范围超出文件");
        return new ByteRange(start, end, true);
    }
}
