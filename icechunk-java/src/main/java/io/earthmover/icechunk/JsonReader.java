package io.earthmover.icechunk;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the JSON documents native methods return, such as snapshot listings and diffs.
 *
 * <p>Objects become unmodifiable {@code Map<String, Object>} in document order, arrays unmodifiable {@code List},
 * integers {@code Long} (or {@code BigInteger} beyond its range), other numbers {@code Double}. The input comes from
 * serde_json, so malformed documents are bugs and fail with {@link IllegalStateException}, as do missing or mistyped
 * fields read through the field accessors.
 */
final class JsonReader {
    private final String text;
    private int pos;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.pos != text.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    static Map<String, Object> readObject(String text) {
        return asObject(parse(text), "document");
    }

    static List<Map<String, Object>> readObjects(String text) {
        List<Object> values = asArray(parse(text), "document");
        List<Map<String, Object>> objects = new ArrayList<>(values.size());
        for (Object value : values) {
            objects.add(asObject(value, "document element"));
        }
        return objects;
    }

    static String string(Map<String, Object> fields, String name) {
        Object value = fields.get(name);
        if (!(value instanceof String)) {
            throw mistyped(name, "string");
        }
        return (String) value;
    }

    static String optionalString(Map<String, Object> fields, String name) {
        return fields.get(name) == null ? null : string(fields, name);
    }

    static Map<String, Object> object(Map<String, Object> fields, String name) {
        return asObject(fields.get(name), name);
    }

    static List<Object> array(Map<String, Object> fields, String name) {
        return asArray(fields.get(name), name);
    }

    static Set<String> stringSet(Map<String, Object> fields, String name) {
        Set<String> strings = new LinkedHashSet<>();
        for (Object value : array(fields, name)) {
            if (!(value instanceof String)) {
                throw mistyped(name, "list of strings");
            }
            strings.add((String) value);
        }
        return Collections.unmodifiableSet(strings);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value, String name) {
        if (!(value instanceof Map)) {
            throw mistyped(name, "object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value, String name) {
        if (!(value instanceof List)) {
            throw mistyped(name, "list");
        }
        return (List<Object>) value;
    }

    private static IllegalStateException mistyped(String name, String type) {
        return new IllegalStateException("native result field " + name + " is missing or not a " + type);
    }

    private Object value() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return true;
            case 'f':
                expect("false");
                return false;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObject() {
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return Collections.unmodifiableMap(map);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String key = parseString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            pos++;
            map.put(key, value());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == '}') {
                return Collections.unmodifiableMap(map);
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> parseArray() {
        pos++;
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return Collections.unmodifiableList(list);
        }
        while (true) {
            list.add(value());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == ']') {
                return Collections.unmodifiableList(list);
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String parseString() {
        int start = ++pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '"') {
                return text.substring(start, pos++);
            }
            if (c == '\\') {
                break;
            }
            pos++;
        }
        StringBuilder out = new StringBuilder().append(text, start, pos);
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = peek();
            pos++;
            switch (escape) {
                case '"':
                case '\\':
                case '/':
                    out.append(escape);
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw error("truncated \\u escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("bad escape");
            }
        }
    }

    private Object parseNumber() {
        int start = pos;
        boolean integer = true;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                integer = false;
            } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                break;
            }
            pos++;
        }
        String number = text.substring(start, pos);
        try {
            if (!integer) {
                return Double.parseDouble(number);
            }
            // Up to 18 digits always fits a long.
            if (number.length() <= 18) {
                return Long.parseLong(number);
            }
            BigInteger value = new BigInteger(number);
            return value.bitLength() < 64 ? (Object) value.longValue() : value;
        } catch (NumberFormatException e) {
            throw error("bad number " + number);
        }
    }

    private void expect(String word) {
        if (!text.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IllegalStateException error(String what) {
        return new IllegalStateException("malformed JSON from the native layer at " + pos + ": " + what);
    }
}
