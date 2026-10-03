package com.local.huaweicast;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LelinkAuthWireTest {
    @Test public void encodesExactLittleEndianGoldenVectorWithoutPadding() {
        byte[] expected = new byte[]{
                0x78, 0x56, 0x34, 0x12, 3, 0, 0, 0, (byte) 0xab, (byte) 0xcd, (byte) 0xef,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0,
                1, 0, 0, 0, 1, 0, 0, 0, 7
        };
        assertArrayEquals(expected, LelinkAuthWire.encode(List.of(
                field(0x12345678L, new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef}),
                field(0xffffffffL, new byte[0]), field(1, new byte[]{7}))));
        List<LelinkAuthWire.Field> fields = LelinkAuthWire.decode(expected);
        assertEquals(3, fields.size());
        assertEquals(0x12345678L, fields.get(0).tag());
        assertArrayEquals(new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef}, fields.get(0).value());
        assertEquals(0xffffffffL, fields.get(1).tag());
        assertEquals(0, fields.get(1).value().length);
        assertEquals(1, fields.get(2).tag());
        assertArrayEquals(new byte[]{7}, fields.get(2).value());
        assertArrayEquals(expected, LelinkAuthWire.encode(fields));
    }

    @Test public void preservesCallerOrderInsteadOfSortingTags() {
        List<LelinkAuthWire.Field> fields = LelinkAuthWire.decode(LelinkAuthWire.encode(List.of(
                field(8, new byte[]{1}), field(2, new byte[]{2}), field(0, new byte[]{3}))));
        assertEquals(8, fields.get(0).tag());
        assertEquals(2, fields.get(1).tag());
        assertEquals(0, fields.get(2).tag());
    }

    @Test public void lengthUsesAllFourLittleEndianBytesWithoutTlv8Splitting() {
        byte[] value = new byte[258];
        Arrays.fill(value, (byte) 0x5a);
        byte[] message = LelinkAuthWire.encode(List.of(field(4, value)));
        byte[] header = new byte[]{4, 0, 0, 0, 2, 1, 0, 0};
        assertEquals(266, message.length);
        assertArrayEquals(header, Arrays.copyOf(message, 8));
        byte[] golden = new byte[266];
        System.arraycopy(header, 0, golden, 0, 8);
        Arrays.fill(golden, 8, golden.length, (byte) 0x5a);
        assertArrayEquals(golden, message);
        assertArrayEquals(value, LelinkAuthWire.decode(golden).get(0).value());
    }

    @Test public void emptyMessageAndZeroLengthFieldsAreValidFraming() {
        assertArrayEquals(new byte[0], LelinkAuthWire.encode(List.of()));
        assertTrue(LelinkAuthWire.decode(new byte[0]).isEmpty());
        byte[] emptyField = new byte[8];
        assertArrayEquals(emptyField, LelinkAuthWire.encode(List.of(field(0, new byte[0]))));
        assertEquals(0, LelinkAuthWire.decode(emptyField).get(0).value().length);
    }

    @Test public void rejectsEveryPartialHeaderIncludingAfterACompleteField() {
        for (int length = 1; length < 8; length++) {
            byte[] partial = new byte[length];
            assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(partial));
            byte[] withCompleteField = new byte[8 + length];
            assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(withCompleteField));
        }
    }

    @Test public void rejectsEveryTruncatedPayload() {
        byte[] complete = LelinkAuthWire.encode(List.of(field(9, new byte[]{1, 2, 3, 4})));
        for (int length = 8; length < complete.length; length++) {
            byte[] truncated = Arrays.copyOf(complete, length);
            assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(truncated));
        }
    }

    @Test public void unsignedLengthsCannotBecomeNegativeOrOverflowAllocations() {
        for (int length : new int[]{0x7fffffff, 0x80000000, 0xffffffff, 32769}) {
            byte[] message = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1).putInt(length).array();
            assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(message));
        }
    }

    @Test public void refusesDuplicateTagsOnEncodeAndDecodeEvenWithEmptyValues() {
        for (long tag : new long[]{0, 0x80000000L, 0xffffffffL}) {
            List<LelinkAuthWire.Field> fields = List.of(field(tag, new byte[0]), field(tag, new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.encode(fields));
            byte[] message = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt((int) tag).putInt(0).putInt((int) tag).putInt(0).array();
            assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(message));
        }
    }

    @Test public void fieldDefensivelyCopiesConstructorAndAccessorArrays() {
        byte[] original = new byte[]{1, 2, 3};
        LelinkAuthWire.Field field = field(3, original);
        original[0] = 9;
        byte[] accessor = field.value();
        accessor[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, field.value());
        assertArrayEquals(new byte[]{1, 2, 3}, LelinkAuthWire.decode(
                LelinkAuthWire.encode(List.of(field))).get(0).value());
    }

    @Test public void decodedFieldsDoNotAliasTheInputAndReturnedListIsImmutable() {
        byte[] message = LelinkAuthWire.encode(List.of(field(7, new byte[]{1, 2})));
        List<LelinkAuthWire.Field> decoded = LelinkAuthWire.decode(message);
        message[0] = 9;
        message[8] = 9;
        decoded.get(0).value()[1] = 9;
        assertEquals(7, decoded.get(0).tag());
        assertArrayEquals(new byte[]{1, 2}, decoded.get(0).value());
        assertThrows(UnsupportedOperationException.class, () -> decoded.add(field(8, new byte[0])));
        assertThrows(UnsupportedOperationException.class, decoded::clear);
    }

    @Test public void encodedArrayDoesNotAliasFieldsOrLaterResults() {
        LelinkAuthWire.Field field = field(1, new byte[]{2});
        byte[] first = LelinkAuthWire.encode(List.of(field));
        first[8] = 9;
        assertArrayEquals(new byte[]{2}, field.value());
        assertEquals(2, LelinkAuthWire.encode(List.of(field))[8]);
    }

    @Test public void acceptsMaximumMessageSizeIncludingFieldHeaders() {
        byte[] value = new byte[LelinkAuthWire.MAX_MESSAGE_SIZE - 8];
        value[0] = 1;
        value[value.length - 1] = 2;
        byte[] message = LelinkAuthWire.encode(List.of(field(5, value)));
        assertEquals(LelinkAuthWire.MAX_MESSAGE_SIZE, message.length);
        assertArrayEquals(value, LelinkAuthWire.decode(message).get(0).value());
    }

    @Test public void rejectsOversizeFieldsAndAggregateMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> field(0, new byte[LelinkAuthWire.MAX_MESSAGE_SIZE - 7]));
        assertThrows(IllegalArgumentException.class,
                () -> LelinkAuthWire.decode(new byte[LelinkAuthWire.MAX_MESSAGE_SIZE + 1]));
        List<LelinkAuthWire.Field> fields = List.of(
                field(0, new byte[LelinkAuthWire.MAX_MESSAGE_SIZE - 16]), field(1, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.encode(fields));
        byte[] maximum = LelinkAuthWire.encode(List.of(
                field(0, new byte[LelinkAuthWire.MAX_MESSAGE_SIZE - 16]), field(1, new byte[0])));
        assertEquals(LelinkAuthWire.MAX_MESSAGE_SIZE, maximum.length);
        assertEquals(2, LelinkAuthWire.decode(maximum).size());
    }

    @Test public void acceptsExactly64FieldsAndRejectsThe65th() {
        List<LelinkAuthWire.Field> fields = new ArrayList<>();
        ByteBuffer message = ByteBuffer.allocate((LelinkAuthWire.MAX_FIELDS + 1) * 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int tag = 0; tag < LelinkAuthWire.MAX_FIELDS; tag++) {
            fields.add(field(tag, new byte[0]));
            message.putInt(tag).putInt(0);
        }
        byte[] maximum = LelinkAuthWire.encode(fields);
        assertEquals(LelinkAuthWire.MAX_FIELDS, LelinkAuthWire.decode(maximum).size());
        assertArrayEquals(Arrays.copyOf(message.array(), maximum.length), maximum);
        fields.add(field(LelinkAuthWire.MAX_FIELDS, new byte[0]));
        message.putInt(LelinkAuthWire.MAX_FIELDS).putInt(0);
        assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.encode(fields));
        assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(message.array()));
    }

    @Test public void tagMustBeWithinUnsigned32BitRange() {
        assertThrows(IllegalArgumentException.class, () -> field(-1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> field(0x100000000L, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> field(Long.MAX_VALUE, new byte[0]));
        assertEquals(0x80000000L, LelinkAuthWire.decode(LelinkAuthWire.encode(
                List.of(field(0x80000000L, new byte[0])))).get(0).tag());
    }

    @Test public void rejectsNullMessagesListsFieldsAndValues() {
        assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.decode(null));
        assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.encode(null));
        assertThrows(IllegalArgumentException.class, () -> field(0, null));
        assertThrows(IllegalArgumentException.class, () -> LelinkAuthWire.encode(Arrays.asList((LelinkAuthWire.Field) null)));
    }

    private static LelinkAuthWire.Field field(long tag, byte[] value) {
        return new LelinkAuthWire.Field(tag, value);
    }
}
