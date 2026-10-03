package com.local.huaweicast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent Lelink auth-message framing; field meanings and authentication are not implemented. */
public final class LelinkAuthWire {
    public static final int MAX_MESSAGE_SIZE = 32 * 1024;
    public static final int MAX_FIELDS = 64;
    private static final int FIELD_HEADER_SIZE = 8;
    private static final long MAX_TAG = 0xffffffffL;

    private LelinkAuthWire() {}

    public record Field(long tag, byte[] value) {
        public Field {
            if (tag < 0 || tag > MAX_TAG || value == null
                    || value.length > MAX_MESSAGE_SIZE - FIELD_HEADER_SIZE) {
                throw new IllegalArgumentException("Invalid Lelink field tag or value length");
            }
            value = value.clone();
        }

        @Override public byte[] value() { return value.clone(); }
    }

    /** Writes fields in caller order, without alignment or padding. */
    public static byte[] encode(List<Field> fields) {
        if (fields == null || fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Invalid Lelink field count");
        }
        List<Field> snapshot = new ArrayList<>(fields.size());
        Set<Long> tags = new HashSet<>();
        int length = 0;
        for (Field field : fields) {
            if (field == null || snapshot.size() == MAX_FIELDS || !tags.add(field.tag)) {
                throw new IllegalArgumentException("Null, duplicate, or excess Lelink field");
            }
            int fieldLength = FIELD_HEADER_SIZE + field.value.length;
            if (fieldLength > MAX_MESSAGE_SIZE - length) {
                throw new IllegalArgumentException("Lelink message exceeds size limit");
            }
            length += fieldLength;
            snapshot.add(field);
        }
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        for (Field field : snapshot) {
            output.putInt((int) field.tag).putInt(field.value.length).put(field.value);
        }
        return output.array();
    }

    /** Reads repeated LE32 tag, LE32 byte length, and raw value fields. */
    public static List<Field> decode(byte[] message) {
        if (message == null || message.length > MAX_MESSAGE_SIZE) {
            throw new IllegalArgumentException("Invalid Lelink message length");
        }
        ByteBuffer input = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN);
        List<Field> fields = new ArrayList<>();
        Set<Long> tags = new HashSet<>();
        while (input.hasRemaining()) {
            if (fields.size() == MAX_FIELDS || input.remaining() < FIELD_HEADER_SIZE) {
                throw new IllegalArgumentException("Truncated or excess Lelink field header");
            }
            long tag = Integer.toUnsignedLong(input.getInt());
            long length = Integer.toUnsignedLong(input.getInt());
            if (!tags.add(tag) || length > input.remaining()) {
                throw new IllegalArgumentException("Duplicate or truncated Lelink field");
            }
            byte[] value = new byte[(int) length];
            input.get(value);
            fields.add(new Field(tag, value));
        }
        return List.copyOf(fields);
    }
}
