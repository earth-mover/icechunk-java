package io.earthmover.icechunk;

import java.util.Map;

/**
 * Builds the JSON documents the native layer reads to configure storage and repositories.
 *
 * <p>The documents only contain strings, numbers, booleans and nested objects, so a few dozen lines replace a JSON
 * library dependency. Null values are omitted, which the native side reads as "not set".
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
