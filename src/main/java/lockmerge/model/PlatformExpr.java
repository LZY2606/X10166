package lockmerge.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 平台条件表达式。所有实例在构造后即处于规范化形式：
 * 否定已下推到比较原子（De Morgan + 双否定消除 + ==/!= 翻转），
 * AND/OR 已拍平、去重并按规范字符串排序。因此 equals/hashCode
 * 基于规范化结构，而非原始字符串。
 */
public abstract class PlatformExpr {

    public static PlatformExpr parse(String input) {
        Parser p = new Parser(input);
        PlatformExpr e = p.parseOr();
        p.skipWs();
        if (!p.atEnd()) {
            throw new PlatformSyntaxException("平台条件存在多余内容: 位置 " + p.pos);
        }
        return e;
    }

    /** 规范化后的规范字符串，稳定且与操作数顺序无关。 */
    public abstract String canonical();

    @Override
    public final boolean equals(Object other) {
        return other instanceof PlatformExpr pe && canonical().equals(pe.canonical());
    }

    @Override
    public final int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public final String toString() {
        return canonical();
    }

    public static final class PlatformSyntaxException extends RuntimeException {
        public PlatformSyntaxException(String message) {
            super(message);
        }
    }

    public static final class Cmp extends PlatformExpr {
        public final String field;
        public final boolean negate; // false: ==, true: !=
        public final String value;

        public Cmp(String field, boolean negate, String value) {
            this.field = field;
            this.negate = negate;
            this.value = value;
        }

        @Override
        public String canonical() {
            return field + (negate ? " != " : " == ") + "\"" + value + "\"";
        }
    }

    public static final class And extends PlatformExpr {
        public final List<PlatformExpr> parts;

        private And(List<PlatformExpr> parts) {
            this.parts = parts;
        }

        @Override
        public String canonical() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(" && ");
                sb.append(parenthesize(parts.get(i), parts.get(i) instanceof Or));
            }
            return sb.toString();
        }
    }

    public static final class Or extends PlatformExpr {
        public final List<PlatformExpr> parts;

        private Or(List<PlatformExpr> parts) {
            this.parts = parts;
        }

        @Override
        public String canonical() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) sb.append(" || ");
                sb.append(parenthesize(parts.get(i), false));
            }
            return sb.toString();
        }
    }

    private static String parenthesize(PlatformExpr e, boolean paren) {
        return paren ? "(" + e.canonical() + ")" : e.canonical();
    }

    static PlatformExpr and(List<PlatformExpr> parts) {
        Set<String> seen = new LinkedHashSet<>();
        List<PlatformExpr> flat = new ArrayList<>();
        for (PlatformExpr p : parts) {
            List<PlatformExpr> items = p instanceof And a ? a.parts : List.of(p);
            for (PlatformExpr item : items) {
                if (seen.add(item.canonical())) flat.add(item);
            }
        }
        if (flat.size() == 1) return flat.get(0);
        flat.sort(java.util.Comparator.comparing(PlatformExpr::canonical));
        return new And(flat);
    }

    static PlatformExpr or(List<PlatformExpr> parts) {
        Set<String> seen = new LinkedHashSet<>();
        List<PlatformExpr> flat = new ArrayList<>();
        for (PlatformExpr p : parts) {
            List<PlatformExpr> items = p instanceof Or o ? o.parts : List.of(p);
            for (PlatformExpr item : items) {
                if (seen.add(item.canonical())) flat.add(item);
            }
        }
        if (flat.size() == 1) return flat.get(0);
        flat.sort(java.util.Comparator.comparing(PlatformExpr::canonical));
        return new Or(flat);
    }

    static PlatformExpr not(PlatformExpr e) {
        if (e instanceof Cmp c) return new Cmp(c.field, !c.negate, c.value);
        if (e instanceof And a) {
            List<PlatformExpr> neg = new ArrayList<>();
            for (PlatformExpr p : a.parts) neg.add(not(p));
            return or(neg);
        }
        if (e instanceof Or o) {
            List<PlatformExpr> neg = new ArrayList<>();
            for (PlatformExpr p : o.parts) neg.add(not(p));
            return and(neg);
        }
        throw new IllegalStateException("unknown expr " + e);
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        PlatformExpr parseOr() {
            List<PlatformExpr> parts = new ArrayList<>();
            parts.add(parseAnd());
            while (true) {
                skipWs();
                if (match("||")) {
                    parts.add(parseAnd());
                } else {
                    return or(parts);
                }
            }
        }

        PlatformExpr parseAnd() {
            List<PlatformExpr> parts = new ArrayList<>();
            parts.add(parseUnary());
            while (true) {
                skipWs();
                if (match("&&")) {
                    parts.add(parseUnary());
                } else {
                    return and(parts);
                }
            }
        }

        PlatformExpr parseUnary() {
            skipWs();
            if (match("!")) {
                return not(parseUnary());
            }
            if (match("(")) {
                PlatformExpr e = parseOr();
                skipWs();
                if (!match(")")) throw new PlatformSyntaxException("缺少右括号: 位置 " + pos);
                return e;
            }
            return parseCmp();
        }

        PlatformExpr parseCmp() {
            skipWs();
            String field = parseIdent();
            skipWs();
            boolean negate;
            if (match("==")) {
                negate = false;
            } else if (match("!=")) {
                negate = true;
            } else {
                throw new PlatformSyntaxException("期望 == 或 !=: 位置 " + pos);
            }
            skipWs();
            String value = parseString();
            return new Cmp(field, negate, value);
        }

        private String parseIdent() {
            skipWs();
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') pos++;
                else break;
            }
            if (start == pos) throw new PlatformSyntaxException("期望字段名: 位置 " + pos);
            return s.substring(start, pos);
        }

        private String parseString() {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw new PlatformSyntaxException("期望字符串字面量: 位置 " + pos);
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '\\' && pos < s.length()) {
                    sb.append(s.charAt(pos++));
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            throw new PlatformSyntaxException("字符串未闭合");
        }

        private boolean match(String token) {
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }
    }
}
