package dev.lockmerge.lock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Parser and normalizer for platform condition expressions.
 *
 * Grammar (OR has the lowest precedence, NOT the highest):
 *   or  := and ('|' and)*
 *   and := unary ('&' unary)*
 *   unary := '!' unary | primary
 *   primary := atom | '(' or ')'
 *   atom := bare-identifier | key '=' value
 *
 * Conditions are compared by their canonical form: a sorted set of sorted
 * minterms (disjunctive normal form). Raw string equality is never used.
 */
public final class PlatformExpr {

    public static final class SyntaxException extends RuntimeException {
        SyntaxException(String message) {
            super(message);
        }
    }

    private sealed interface Expr permits Atom, Not, And, Or { }
    private record Atom(String literal) implements Expr { }
    private record Not(Expr inner) implements Expr { }
    private record And(List<Expr> parts) implements Expr { }
    private record Or(List<Expr> parts) implements Expr { }

    /** One minterm: literals that must hold, negated literals that must not. */
    public record Minterm(Set<String> positive, Set<String> negative)
            implements Comparable<Minterm> {

        @Override
        public int compareTo(Minterm other) {
            int cmp = String.join("&", positive).compareTo(String.join("&", other.positive));
            if (cmp != 0) {
                return cmp;
            }
            return String.join("&", negative).compareTo(String.join("&", other.negative));
        }
    }

    private final List<String> tokens;
    private int pos;

    private PlatformExpr(List<String> tokens) {
        this.tokens = tokens;
    }

    /** Returns null for an absent condition; otherwise the canonical minterm set key. */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        PlatformExpr parser = new PlatformExpr(tokenize(raw));
        Expr expr = parser.parseOr();
        if (!parser.atEnd()) {
            throw new SyntaxException("unexpected token '" + parser.peek() + "'");
        }
        Set<Minterm> dnf = toDnf(expr);
        if (dnf.isEmpty()) {
            return "false";
        }
        if (dnf.size() == 1 && dnf.iterator().next().positive().isEmpty()
                && dnf.iterator().next().negative().isEmpty()) {
            return "true";
        }
        List<String> terms = new ArrayList<>();
        for (Minterm minterm : new TreeSet<>(dnf)) {
            List<String> parts = new ArrayList<>();
            for (String lit : minterm.positive()) {
                parts.add(lit);
            }
            for (String lit : minterm.negative()) {
                parts.add("!" + lit);
            }
            terms.add(String.join("&", parts));
        }
        return String.join("|", terms);
    }

    public static boolean equivalent(String a, String b) {
        String ca = canonical(a);
        String cb = canonical(b);
        if (ca == null) {
            return cb == null;
        }
        return ca.equals(cb);
    }

    // ---- DNF conversion -------------------------------------------------

    private static Set<Minterm> toDnf(Expr expr) {
        if (expr instanceof Atom atom) {
            return Set.of(new Minterm(
                    new TreeSet<>(Set.of(atom.literal())), new TreeSet<>()));
        }
        if (expr instanceof Not not) {
            return negate(not.inner());
        }
        if (expr instanceof And and) {
            Set<Minterm> combined = new TreeSet<>();
            combined.add(new Minterm(new TreeSet<>(), new TreeSet<>()));
            for (Expr part : and.parts()) {
                combined = cross(combined, toDnf(part));
            }
            return combined;
        }
        Or orExpr = (Or) expr;
        Set<Minterm> union = new TreeSet<>();
        for (Expr part : orExpr.parts()) {
            union.addAll(toDnf(part));
        }
        return union;
    }

    private static Set<Minterm> cross(Set<Minterm> left, Set<Minterm> right) {
        Set<Minterm> result = new TreeSet<>();
        for (Minterm a : left) {
            for (Minterm b : right) {
                Set<String> positive = new TreeSet<>(a.positive());
                Set<String> negative = new TreeSet<>(a.negative());
                positive.addAll(b.positive());
                negative.addAll(b.negative());
                boolean contradiction = positive.stream().anyMatch(negative::contains);
                if (!contradiction) {
                    result.add(new Minterm(positive, negative));
                }
            }
        }
        return result;
    }

    private static Set<Minterm> negate(Expr expr) {
        if (expr instanceof Atom atom) {
            return Set.of(new Minterm(
                    new TreeSet<>(), new TreeSet<>(Set.of(atom.literal()))));
        }
        if (expr instanceof Not not) {
            return toDnf(not.inner());
        }
        // !(a&b) = !a|!b ; !(a|b) = !a&!b (De Morgan)
        if (expr instanceof And and) {
            Set<Minterm> union = new TreeSet<>();
            for (Expr part : and.parts()) {
                union.addAll(negate(part));
            }
            return union;
        }
        Or orExpr = (Or) expr;
        Set<Minterm> combined = new TreeSet<>();
        combined.add(new Minterm(new TreeSet<>(), new TreeSet<>()));
        for (Expr part : orExpr.parts()) {
            combined = cross(combined, negate(part));
        }
        return combined;
    }

    // ---- Parser ---------------------------------------------------------

    private Expr parseOr() {
        List<Expr> parts = new ArrayList<>(List.of(parseAnd()));
        while ("|".equals(peek())) {
            next();
            parts.add(parseAnd());
        }
        return parts.size() == 1 ? parts.get(0) : new Or(parts);
    }

    private Expr parseAnd() {
        List<Expr> parts = new ArrayList<>(List.of(parseUnary()));
        while ("&".equals(peek())) {
            next();
            parts.add(parseUnary());
        }
        return parts.size() == 1 ? parts.get(0) : new And(parts);
    }

    private Expr parseUnary() {
        if ("!".equals(peek())) {
            next();
            return new Not(parseUnary());
        }
        return parsePrimary();
    }

    private Expr parsePrimary() {
        String token = peek();
        if (token == null) {
            throw new SyntaxException("expected condition, got end of expression");
        }
        if ("(".equals(token)) {
            next();
            Expr inner = parseOr();
            if (!")".equals(peek())) {
                throw new SyntaxException("expected ')'");
            }
            next();
            return inner;
        }
        if (isAtom(token)) {
            next();
            return new Atom(token);
        }
        throw new SyntaxException("expected atom or '(', got '" + token + "'");
    }

    private static boolean isAtom(String token) {
        if (token.isBlank()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == '!'
                    || ch == '&' || ch == '|') {
                return false;
            }
        }
        return true;
    }

    private String peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private String next() {
        if (atEnd()) {
            throw new SyntaxException("unexpected end of expression");
        }
        return tokens.get(pos++);
    }

    private boolean atEnd() {
        return pos >= tokens.size();
    }

    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '!' || ch == '&' || ch == '|' || ch == '(' || ch == ')') {
                out.add(String.valueOf(ch));
                i++;
            } else {
                int start = i;
                while (i < raw.length()) {
                    char c = raw.charAt(i);
                    if (Character.isWhitespace(c) || c == '&' || c == '|'
                            || c == '(' || c == ')') {
                        break;
                    }
                    if (c == '!' && i != start) {
                        break;
                    }
                    i++;
                }
                out.add(raw.substring(start, i));
            }
        }
        return out;
    }
}
