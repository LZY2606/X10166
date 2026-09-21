package lockmerge.platform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Boolean platform conditions with a canonical disjunctive-normal form.
 *
 * <p>Grammar: {@code or := and ('|' and)*}, {@code and := unary ('&' unary)*},
 * unary is {@code !unary} or an atom. Atoms are parenthesized groups,
 * {@code true}/{@code false}, bare tags ({@code linux}) or comparisons
 * ({@code os=macos}, {@code arch!=arm64}).
 *
 * <p>Two conditions are semantically equal iff their canonical DNF strings
 * compare equal. Raw source spelling (spaces, ordering, double negation,
 * De Morgan) is irrelevant.
 */
public final class PlatformExpr {

    /** Canonical DNF: null means constant true; empty inner sets never occur. */
    private final List<Set<String>> dnf;
    private final boolean alwaysFalse;

    private PlatformExpr(List<Set<String>> dnf, boolean alwaysFalse) {
        this.dnf = dnf;
        this.alwaysFalse = alwaysFalse;
    }

    public static PlatformExpr always() {
        return new PlatformExpr(null, false);
    }

    public static PlatformExpr never() {
        return new PlatformExpr(List.of(), true);
    }

    public static PlatformExpr parse(String text) {
        if (text == null || text.isBlank()) {
            return always();
        }
        Parser parser = new Parser(text);
        List<Set<String>> dnf = parser.parseOr();
        parser.expectEnd();
        if (dnf == null) {
            return always();
        }
        List<Set<String>> canonical = normalizeOr(dnf);
        if (canonical == null) {
            return always();
        }
        if (canonical.isEmpty()) {
            return never();
        }
        return new PlatformExpr(canonical, false);
    }

    /** Canonical string, re-parseable by {@link #parse(String)}. */
    public String canonical() {
        if (dnf == null) {
            return "true";
        }
        if (alwaysFalse) {
            return "false";
        }
        List<String> terms = new ArrayList<>();
        for (Set<String> term : dnf) {
            terms.add(String.join(" & ", term));
        }
        return String.join(" | ", terms);
    }

    public boolean isAlwaysTrue() {
        return dnf == null;
    }

    public boolean isAlwaysFalse() {
        return alwaysFalse;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlatformExpr that)) {
            return false;
        }
        return canonical().equals(that.canonical());
    }

    @Override
    public int hashCode() {
        return canonical().hashCode();
    }

    @Override
    public String toString() {
        return canonical();
    }

    // ---- DNF algebra --------------------------------------------------

    static List<Set<String>> normalizeOr(List<Set<String>> terms) {
        Set<Set<String>> dedup = new LinkedHashSet<>();
        for (Set<String> term : terms) {
            if (term == null) {
                return null; // constant true absorbs a whole OR
            }
            if (term.isEmpty()) {
                continue; // false contributes nothing to OR
            }
            dedup.add(copySorted(term));
        }
        List<Set<String>> list = new ArrayList<>(dedup);
        list.removeIf(PlatformExpr::hasComplement);
        absorb(list);
        return list;
    }

    static Set<String> normalizeAnd(List<Set<String>> conjuncts) {
        Set<String> term = new TreeSet<>();
        for (Set<String> conjunct : conjuncts) {
            if (conjunct != null && conjunct.isEmpty()) {
                return Set.of(); // false in AND
            }
            if (conjunct != null) {
                term.addAll(conjunct);
            }
        }
        if (hasComplement(term)) {
            return Set.of();
        }
        return term;
    }

    /** Cartesian product of DNF lists; {@code null} means true. */
    static List<Set<String>> andDnf(List<Set<String>> a, List<Set<String>> b) {
        if (a != null && a.isEmpty()) {
            return List.of();
        }
        if (b != null && b.isEmpty()) {
            return List.of();
        }
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        List<Set<String>> product = new ArrayList<>();
        for (Set<String> ta : a) {
            for (Set<String> tb : b) {
                Set<String> term = new TreeSet<>(ta);
                term.addAll(tb);
                if (hasComplement(term)) {
                    continue;
                }
                product.add(term);
            }
        }
        return normalizeOr(product);
    }

    static List<Set<String>> orDnf(List<Set<String>> a, List<Set<String>> b) {
        if (a == null || b == null) {
            return null;
        }
        List<Set<String>> merged = new ArrayList<>(a);
        merged.addAll(b);
        return normalizeOr(merged);
    }

    static List<Set<String>> notDnf(List<Set<String>> dnfValue) {
        if (dnfValue == null) {
            return List.of(); // !true = false
        }
        if (dnfValue.isEmpty()) {
            return null; // !false = true
        }
        // NOT of a DNF: for every term T=(l1 & l2 & ...) the negation is the
        // clause (!l1 | !l2 | ...). The conjunction of all clauses is expanded
        // to DNF via the distributive law.
        List<List<String>> clauses = new ArrayList<>();
        for (Set<String> term : dnfValue) {
            List<String> clause = new ArrayList<>();
            for (String literal : term) {
                clause.add(negateLiteral(literal));
            }
            clauses.add(clause);
        }
        List<Set<String>> result = new ArrayList<>();
        result.add(new TreeSet<>());
        for (List<String> clause : clauses) {
            List<Set<String>> expanded = new ArrayList<>();
            for (Set<String> partial : result) {
                for (String literal : clause) {
                    TreeSet<String> candidate = new TreeSet<>(partial);
                    candidate.add(literal);
                    if (!hasComplement(candidate)) {
                        expanded.add(candidate);
                    }
                }
            }
            result = expanded;
        }
        return normalizeOr(result);
    }

    private static boolean hasComplement(Set<String> term) {
        for (String literal : term) {
            if (term.contains(negateLiteral(literal))) {
                return true;
            }
        }
        return false;
    }

    private static void absorb(List<Set<String>> terms) {
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < terms.size(); i++) {
                for (int j = 0; j < terms.size(); j++) {
                    if (i != j && terms.get(j).containsAll(terms.get(i))) {
                        terms.remove(i);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        terms.sort((x, y) -> {
            int bySize = Integer.compare(x.size(), y.size());
            if (bySize != 0) {
                return bySize;
            }
            return String.join("\u0000", x).compareTo(String.join("\u0000", y));
        });
    }

    private static Set<String> copySorted(Set<String> term) {
        return new TreeSet<>(term);
    }

    static String negateLiteral(String literal) {
        int neq = literal.indexOf("!=");
        if (neq > 0) {
            return literal.substring(0, neq) + "=" + literal.substring(neq + 2);
        }
        if (literal.startsWith("!")) {
            String inner = literal.substring(1);
            int eq = inner.indexOf('=');
            if (eq > 0) {
                return inner.substring(0, eq) + "!=" + inner.substring(eq + 1);
            }
            return inner;
        }
        int eq = literal.indexOf('=');
        if (eq > 0) {
            return literal.substring(0, eq) + "!=" + literal.substring(eq + 1);
        }
        return "!" + literal;
    }

    // ---- Parser -------------------------------------------------------

    private enum TokKind { IDENT, OP_OR, OP_AND, OP_NOT, LPAREN, RPAREN,
        OP_EQ, OP_NE, KEY_TRUE, KEY_FALSE, EOF }

    private static final class Token {
        final TokKind kind;
        final String text;
        final int pos;

        Token(TokKind kind, String text, int pos) {
            this.kind = kind;
            this.text = text;
            this.pos = pos;
        }
    }

    private static final class Parser {
        private final String src;
        private final List<Token> tokens = new ArrayList<>();
        private int cursor;

        Parser(String src) {
            this.src = src;
            lex();
        }

        private void lex() {
            int i = 0;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                switch (c) {
                    case '|' -> {
                        tokens.add(new Token(TokKind.OP_OR, "|", i));
                        i += 1;
                    }
                    case '&' -> {
                        tokens.add(new Token(TokKind.OP_AND, "&", i));
                        i += 1;
                    }
                    case '!' -> {
                        if (i + 1 < src.length() && src.charAt(i + 1) == '=') {
                            tokens.add(new Token(TokKind.OP_NE, "!=", i));
                            i += 2;
                        } else {
                            tokens.add(new Token(TokKind.OP_NOT, "!", i));
                            i++;
                        }
                    }
                    case '(' -> {
                        tokens.add(new Token(TokKind.LPAREN, "(", i));
                        i++;
                    }
                    case ')' -> {
                        tokens.add(new Token(TokKind.RPAREN, ")", i));
                        i++;
                    }
                    case '=' -> {
                        tokens.add(new Token(TokKind.OP_EQ, "=", i));
                        i++;
                    }
                    default -> {
                        if (isIdentStart(c)) {
                            int start = i;
                            i++;
                            while (i < src.length() && isIdentPart(src.charAt(i))) {
                                i++;
                            }
                            String word = src.substring(start, i).toLowerCase();
                            TokKind kind = switch (word) {
                                case "true" -> TokKind.KEY_TRUE;
                                case "false" -> TokKind.KEY_FALSE;
                                default -> TokKind.IDENT;
                            };
                            tokens.add(new Token(kind, word, start));
                        } else {
                            throw new PlatformSyntaxException(
                                    "unexpected character '" + c + "' at position " + (i + 1));
                        }
                    }
                }
            }
            tokens.add(new Token(TokKind.EOF, "", i));
        }

        private static boolean isIdentStart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
        }

        private static boolean isIdentPart(char c) {
            return isIdentStart(c);
        }

        private Token peek() {
            return tokens.get(cursor);
        }

        private Token consume() {
            return tokens.get(cursor++);
        }

        private void expect(TokKind kind) {
            if (peek().kind != kind) {
                throw new PlatformSyntaxException(
                        "expected " + kind + " at position " + (peek().pos + 1)
                                + " but found '" + srcAt(peek()) + "'");
            }
            cursor++;
        }

        private String srcAt(Token token) {
            return token.kind == TokKind.EOF ? "end of expression" : token.text;
        }

        void expectEnd() {
            if (peek().kind != TokKind.EOF) {
                throw new PlatformSyntaxException(
                        "unexpected token '" + peek().text + "' at position " + (peek().pos + 1));
            }
        }

        List<Set<String>> parseOr() {
            List<Set<String>> left = parseAnd();
            while (peek().kind == TokKind.OP_OR) {
                consume();
                List<Set<String>> right = parseAnd();
                left = orDnf(left, right);
            }
            return left;
        }

        private List<Set<String>> parseAnd() {
            List<Set<String>> left = parseUnary();
            while (peek().kind == TokKind.OP_AND) {
                consume();
                List<Set<String>> right = parseUnary();
                left = andDnf(left, right);
            }
            return left;
        }

        private List<Set<String>> parseUnary() {
            if (peek().kind == TokKind.OP_NOT) {
                consume();
                return notDnf(parseUnary());
            }
            return parseAtom();
        }

        private List<Set<String>> parseAtom() {
            Token token = consume();
            return switch (token.kind) {
                case LPAREN -> {
                    List<Set<String>> inner = parseOr();
                    expect(TokKind.RPAREN);
                    yield inner;
                }
                case KEY_TRUE -> null;
                case KEY_FALSE -> List.of();
                case IDENT -> parseLiteralTail(token);
                default -> throw new PlatformSyntaxException(
                        "expected platform atom at position " + (token.pos + 1)
                                + " but found '" + srcAt(token) + "'");
            };
        }

        private List<Set<String>> parseLiteralTail(Token key) {
            String literal = key.text;
            if (peek().kind == TokKind.OP_EQ) {
                consume();
                Token value = consume();
                if (value.kind != TokKind.IDENT) {
                    throw new PlatformSyntaxException(
                            "expected value after '=' at position " + (value.pos + 1));
                }
                literal = key.text + "=" + value.text;
            } else if (peek().kind == TokKind.OP_NE) {
                consume();
                Token value = consume();
                if (value.kind != TokKind.IDENT) {
                    throw new PlatformSyntaxException(
                            "expected value after '!=' at position " + (value.pos + 1));
                }
                literal = key.text + "!=" + value.text;
            }
            return List.of(Set.of(literal));
        }
    }
}
