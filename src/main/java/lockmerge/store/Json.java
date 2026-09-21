package lockmerge.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 无依赖的最小 JSON 读写器。值类型：Map / List / String / Double / Boolean / null。 */
public final class Json {
    private Json() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值类型: " + v.getClass());
        }
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) throw new JsonException("JSON 末尾存在多余内容");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new JsonException("期望 JSON 对象");
        return (Map<String, Object>) v;
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            ws();
            if (pos >= s.length()) throw new JsonException("意外的 JSON 结尾");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                m.put(key, value());
                ws();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else {
                    expect('}');
                    return m;
                }
            }
        }

        private List<Object> array() {
            pos++;
            List<Object> list = new ArrayList<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                ws();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String string() {
            if (pos >= s.length() || s.charAt(pos) != '"') throw new JsonException("期望字符串: 位置 " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonException("字符串未闭合");
        }

        private Object number() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new JsonException("无法解析的值: 位置 " + pos);
            return Double.parseDouble(s.substring(start, pos));
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw new JsonException("无法解析的字面量: 位置 " + pos);
            pos += word.length();
            return value;
        }

        private void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) throw new JsonException("期望 '" + c + "': 位置 " + pos);
            pos++;
        }
    }
}
