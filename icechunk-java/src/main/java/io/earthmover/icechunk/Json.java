package io.earthmover.icechunk;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the JSON documents the native layer reads to configure storage and repositories.
 *
 * <p>The documents only contain strings, numbers, booleans and nested objects, so a few dozen lines replace a JSON
 * library dependency. Null values are omitted, which the native side reads as "not set". {@link #putValue} also writes
 * arbitrary JSON values, such as commit metadata, where null is kept.
 */
final class Json {
    private final StringBuilder out = new StringBuilder("{");
    private boolean empty = true;

    static Json object() {
        return new Json();
    }

    Json put(String name, String value) {
        if (value != null) {
            key(name);
            quote(out, value);
        }
        return this;
    }

    Json put(String name, Number value) {
        if (value != null) {
            key(name).append(value);
        }
        return this;
    }

    Json put(String name, boolean value) {
        key(name).append(value);
        return this;
    }

    Json put(String name, Json value) {
        if (value != null) {
            key(name).append(value);
        }
        return this;
    }

    Json put(String name, Map<String, String> values) {
        if (values != null) {
            Json nested = object();
            values.forEach(nested::put);
            put(name, nested);
        }
        return this;
    }

    /**
     * Write {@code value} as JSON: a {@code String}, {@code Boolean}, {@code Map} with string keys, {@code Collection},
     * null, or a number of type {@code Integer}, {@code Long}, {@code Short}, {@code Byte}, {@code Double},
     * {@code Float} or {@code BigInteger}, with maps and collections nested at most {@value #MAX_DEPTH} deep.
     *
     * @throws IllegalArgumentException for any other type, deeper nesting, a non-finite number, or a
     *     {@code BigInteger} outside the range of a 64-bit signed or unsigned integer, which the native side would
     *     round to a double
     */
    Json putValue(String name, Object value) {
        writeValue(key(name), value, 0);
        return this;
    }

    /** serde_json stops parsing at 128 levels; this leaves room for the documents that contain the value. */
    static final int MAX_DEPTH = 100;

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger UNSIGNED_LONG_MAX =
            BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON values nest deeper than " + MAX_DEPTH + " levels");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON has no representation for " + value);
            }
            out.append(value);
        } else if (value instanceof Long
                || value instanceof Integer
                || value instanceof Short
                || value instanceof Byte) {
            out.append(value);
        } else if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.compareTo(LONG_MIN) < 0 || integer.compareTo(UNSIGNED_LONG_MAX) > 0) {
                throw new IllegalArgumentException("integer out of the 64-bit range: " + value);
            }
            out.append(value);
        } else if (value instanceof Map) {
            Json nested = object();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON object keys must be strings: " + entry.getKey());
                }
                writeValue(nested.key((String) entry.getKey()), entry.getValue(), depth + 1);
            }
            out.append(nested);
        } else if (value instanceof Collection) {
            out.append('[');
            boolean first = true;
            for (Object element : (Collection<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(out, element, depth + 1);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException(
                    "not a JSON value: " + value.getClass().getName());
        }
    }

    private static final Instant FIRST_INSTANT = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant LAST_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    /**
     * {@code at} as an RFC 3339 timestamp, which the native side parses.
     *
     * @throws NullPointerException if {@code at} is null
     * @throws IllegalArgumentException if {@code at} is outside the years 0000 to 9999, which RFC 3339 cannot express
     */
    static String timestamp(String what, Instant at) {
        Objects.requireNonNull(at, what);
        if (at.isBefore(FIRST_INSTANT) || at.isAfter(LAST_INSTANT)) {
            throw new IllegalArgumentException(what + " out of range: " + at);
        }
        return at.toString();
    }

    /** Insert an already serialized JSON value. */
    Json putRaw(String name, String json) {
        if (json != null) {
            key(name).append(json);
        }
        return this;
    }

    private StringBuilder key(String name) {
        if (!empty) {
            out.append(',');
        }
        empty = false;
        quote(out, name);
        return out.append(':');
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    @Override
    public String toString() {
        return out + "}";
    }
}
