package org.example.retention;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static final class JObject extends LinkedHashMap<String, java.lang.Object> {
    }

    public static final class JArray extends ArrayList<java.lang.Object> {
    }

    public static java.lang.Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected trailing JSON at position " + parser.position);
        }
        return value;
    }

    public static Map<String, Object> parseObject(String text) {
        java.lang.Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, java.lang.Object> map = (Map<String, java.lang.Object>) value;
        return map;
    }

    public static String write(java.lang.Object value) {
        StringBuilder builder = new StringBuilder();
        write(value, builder);
        return builder.toString();
    }

    public static String string(Map<String, java.lang.Object> map, String key) {
        java.lang.Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    public static Integer integer(Map<String, java.lang.Object> map, String key) {
        java.lang.Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<java.lang.Object> list(java.lang.Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    private static void write(java.lang.Object value, StringBuilder builder) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String text) {
            writeString(text, builder);
        } else if (value instanceof Boolean || value instanceof Number) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), builder);
                builder.append(':');
                write(entry.getValue(), builder);
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                write(item, builder);
            }
            builder.append(']');
        } else {
            writeString(value.toString(), builder);
        }
    }

    private static void writeString(String value, StringBuilder builder) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private java.lang.Object readValue() {
            skipWhitespace();
            if (end()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(position);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't' || c == 'f') {
                return readBoolean();
            }
            if (c == 'n') {
                return readNull();
            }
            return readNumber();
        }

        private java.lang.Object readObject() {
            JObject object = new JObject();
            expect('{');
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                object.put(key, readValue());
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private java.lang.Object readArray() {
            JArray array = new JArray();
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            while (true) {
                array.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    if (position >= text.length()) {
                        throw error("Unterminated escape");
                    }
                    char escaped = text.charAt(position++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (position + 4 > text.length()) {
                                throw error("Bad unicode escape");
                            }
                            builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                            position += 4;
                        }
                        default -> throw error("Unsupported escape: " + escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw error("Expected boolean");
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw error("Expected null");
        }

        private Number readNumber() {
            int start = position;
            consume('-');
            readDigits();
            if (consume('.')) {
                readDigits();
            }
            if (!end() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                position++;
                if (!end() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                    position++;
                }
                readDigits();
            }
            String number = text.substring(start, position);
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.valueOf(number);
            }
            try {
                return Long.valueOf(number);
            } catch (NumberFormatException ignored) {
                return Double.valueOf(number);
            }
        }

        private void readDigits() {
            int start = position;
            while (!end() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw error("Expected number");
            }
        }

        private void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean end() {
            return position >= text.length();
        }

        private boolean consume(char expected) {
            if (!end() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
