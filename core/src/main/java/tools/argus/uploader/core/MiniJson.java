package tools.argus.uploader.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the backend's small replies, so the core module needs no library: objects become
 * {@link Map}s, arrays {@link List}s, numbers {@link Double}s, plus {@link String}, {@link Boolean}
 * and {@code null}. Throws {@link IllegalArgumentException} on anything malformed.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 16;

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        parser.skipSpace();
        Object value = parser.value(0);
        parser.skipSpace();
        if (parser.pos != text.length()) {
            throw parser.error("unexpected text after the value");
        }
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, value(depth + 1));
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(value(depth + 1));
            skipSpace();
            if (peek() == ',') {
                pos++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated escape");
            }
            char escape = text.charAt(pos++);
            switch (escape) {
                case '"', '\\', '/' -> sb.append(escape);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("short \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                }
                default -> throw error("bad escape");
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw error("unexpected character '" + text.charAt(pos) + "'");
        }
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected text");
        }
        pos += word.length();
        return value;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("bad JSON at " + pos + ": " + message);
    }
}
