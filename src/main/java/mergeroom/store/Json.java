package mergeroom.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON value model + pretty writer + recursive-descent parser. */
public final class Json {

    private Json() {
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static List<Object> arr(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writeValue(sb, e.getValue(), indent + 1);
            }
            sb.append('\n');
            indent(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                first = false;
                indent(sb, indent + 1);
                writeValue(sb, item, indent + 1);
            }
            sb.append('\n');
            indent(sb, indent);
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append("  ".repeat(Math.max(0, n)));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = new Parser(text).parse();
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object parse() {
            skipWs();
            Object v = readValue();
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("trailing characters at " + pos);
            }
            return v;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private Object readValue() {
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(pos);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBoolean();
            if (c == 'n') return readNull();
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + pos);
                }
                pos++;
                m.put(key, readValue());
                skipWs();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated object");
                }
                char sep = s.charAt(pos);
                if (sep == ',') {
                    pos++;
                } else if (sep == '}') {
                    pos++;
                    return m;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at " + pos);
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated array");
                }
                char sep = s.charAt(pos);
                if (sep == ',') {
                    pos++;
                } else if (sep == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at " + pos);
                }
            }
        }

        private String readString() {
            if (s.charAt(pos) != '"') {
                throw new IllegalArgumentException("expected string at " + pos);
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("bad escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Number readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos))
                    || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E'
                    || s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            String token = s.substring(start, pos);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }
    }
}
