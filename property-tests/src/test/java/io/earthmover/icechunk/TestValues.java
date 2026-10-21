package io.earthmover.icechunk;

import static dev.hegel.Generators.integers;

import dev.hegel.TestCase;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/** Chunk values for the state machines, and the buffers they are handed to {@link Store#set} in. */
final class TestValues {
    private TestValues() {}

    /** The ways a caller can hand bytes to {@link Store#set}. */
    enum BufferKind {
        HEAP {
            @Override
            ByteBuffer wrap(byte[] value) {
                return ByteBuffer.wrap(value);
            }
        },
        /** A heap buffer positioned after the start of its array, with bytes after the value too. */
        HEAP_WINDOW {
            @Override
            ByteBuffer wrap(byte[] value) {
                ByteBuffer buffer = ByteBuffer.wrap(padded(value));
                buffer.position(3);
                buffer.limit(3 + value.length);
                return buffer;
            }
        },
        /** A slice of a heap buffer, whose array offset is not zero. */
        HEAP_SLICE {
            @Override
            ByteBuffer wrap(byte[] value) {
                return ByteBuffer.wrap(padded(value), 3, value.length).slice();
            }
        },
        /** A read-only heap buffer, whose array Java does not expose. */
        READ_ONLY {
            @Override
            ByteBuffer wrap(byte[] value) {
                return ByteBuffer.wrap(value).asReadOnlyBuffer();
            }
        },
        DIRECT {
            @Override
            ByteBuffer wrap(byte[] value) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(value.length);
                buffer.put(value);
                buffer.flip();
                return buffer;
            }
        },
        /** A direct buffer with bytes before and after the value. */
        DIRECT_WINDOW {
            @Override
            ByteBuffer wrap(byte[] value) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(value.length + 7);
                while (buffer.hasRemaining()) {
                    buffer.put((byte) 0x5A);
                }
                buffer.position(3);
                buffer.put(value);
                buffer.position(3);
                buffer.limit(3 + value.length);
                return buffer;
            }
        };

        abstract ByteBuffer wrap(byte[] value);

        private static byte[] padded(byte[] value) {
            byte[] padded = new byte[value.length + 7];
            Arrays.fill(padded, (byte) 0x5A);
            System.arraycopy(value, 0, padded, 3, value.length);
            return padded;
        }
    }

    /**
     * A chunk value: mostly small, some larger than the default inline threshold of 512 bytes, and a few larger than
     * the 64 KiB above which a direct buffer is read in place, and at least {@code minLength} bytes long. The bytes come
     * from a drawn seed, so a large value costs one draw.
     */
    static byte[] draw(TestCase tc, int minLength) {
        int tier = tc.draw(integers().min(0).max(9), "sizeTier");
        int length;
        if (tier < 6) {
            length = tc.draw(integers().min(minLength).max(16), "length");
        } else if (tier < 9) {
            length = tc.draw(integers().min(17).max(2000), "length");
        } else {
            length = tc.draw(integers().min(65_000).max(70_000), "length");
        }
        byte[] value = new byte[length];
        new Random(tc.draw(integers(), "seed")).nextBytes(value);
        return value;
    }
}
