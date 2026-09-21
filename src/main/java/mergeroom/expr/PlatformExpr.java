package mergeroom.expr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Normalized platform condition in disjunctive normal form.
 *
 * Grammar (no whitespace allowed inside an expression):
 *   expr := or
 *   or   := and ('|' and)*
 *   and  := unary ('&' unary)*
 *   unary:= '!' unary | atom
 *   atom := ident | '(' or ')'
 *
 * Semantic equivalence is decided on the canonical DNF string, never on the
 * raw source spelling: "a&b" equals "b&a", "!!a" equals "a",
 * "(a|b)&c" equals "(a&c)|(b&c)" and so on.
 */
public final class PlatformExpr {

    public static final class ExprParseException extends Exception {
        public ExprParseException(String message) {
            super(message);
        }
    }

    private record Literal(String atom, boolean negated) {
        Literal negate() {
            return new Literal(atom, !negated);
        }

        String render() {
            return negated ? "!" + atom : atom;
        }
    }

    private sealed interface Tree permits LitNode, NotNode, AndNode, OrNode {}
    private record LitNode(String atom) implements Tree {}
    private record NotNode(Tree child) implements Tree {}
    private record AndNode(Tree left, Tree right) implements Tree {}
    private record OrNode(Tree left, Tree right) implements Tree {}

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Tree parse() throws ExprParseException {
            Tree t = parseOr();
            skip();
            if (pos != s.length()) {
                throw new ExprParseException("unexpected character '" + s.charAt(pos) + "'");
            }
            return t;
        }

        private void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private Tree parseOr() throws ExprParseException {
            Tree left = parseAnd();
            while (true) {
                skip();
                if (pos < s.length() && s.charAt(pos) == '|') {
                    pos++;
                    left = new OrNode(left, parseAnd());
                } else {
                    return left;
                }
            }
        }

        private Tree parseAnd() throws ExprParseException {
            Tree left = parseUnary();
            while (true) {
                skip();
                if (pos < s.length() && s.charAt(pos) == '&') {
                    pos++;
                    left = new AndNode(left, parseUnary());
                } else {
                    return left;
                }
            }
        }

        private Tree parseUnary() throws ExprParseException {
            skip();
            if (pos >= s.length()) {
                throw new ExprParseException("expected condition expression");
            }
            char c = s.charAt(pos);
            if (c == '!') {
                pos++;
                return new NotNode(parseUnary());
            }
            return parseAtom();
        }

        private Tree parseAtom() throws ExprParseException {
            skip();
            if (pos >= s.length()) {
                throw new ExprParseException("expected condition expression");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Tree inner = parseOr();
                skip();
                if (pos >= s.length() || s.charAt(pos) != ')') {
                    throw new ExprParseException("missing closing ')'");
                }
                pos++;
                return inner;
            }
            int start = pos;
            while (pos < s.length() && isIdentChar(s.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw new ExprParseException("expected identifier, got '" + c + "'");
            }
            return new LitNode(s.substring(start, pos));
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '=';
        }
    }

    /** DNF: list of conjunctions, each a set of literals. */
    private final List<Set<Literal>> dnf;
    private final String canonical;

    private PlatformExpr(List<Set<Literal>> dnf) {
        this.dnf = dnf;
        this.canonical = render(dnf);
    }

    public static PlatformExpr parse(String raw) throws ExprParseException {
        if (raw == null || raw.isBlank()) {
            throw new ExprParseException("empty platform expression");
        }
        Tree tree = new Parser(raw).parse();
        return new PlatformExpr(simplify(toDnf(tree)));
    }

    public String canonical() {
        return canonical;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlatformExpr p && p.canonical.equals(canonical);
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }

    private static List<Set<Literal>> toDnf(Tree t) {
        if (t instanceof LitNode l) {
            List<Set<Literal>> r = new ArrayList<>();
            r.add(new LinkedHashSet<>(Set.of(new Literal(l.atom(), false))));
            return r;
        }
        if (t instanceof NotNode n) {
            return negate(toDnf(n.child()));
        }
        if (t instanceof AndNode a) {
            return intersect(toDnf(a.left()), toDnf(a.right()));
        }
        if (t instanceof OrNode o) {
            List<Set<Literal>> r = new ArrayList<>(toDnf(o.left()));
            r.addAll(toDnf(o.right()));
            return r;
        }
        throw new IllegalStateException();
    }

    private static List<Set<Literal>> negate(List<Set<Literal>> d) {
        // !(A|B) = !A & !B; !(a&b) = !a | !b
        List<Set<Literal>> result = null;
        for (Set<Literal> conjunction : d) {
            List<Set<Literal>> alternatives = new ArrayList<>();
            for (Literal lit : conjunction) {
                alternatives.add(new LinkedHashSet<>(Set.of(lit.negate())));
            }
            result = (result == null) ? alternatives : intersect(result, alternatives);
        }
        return result == null ? List.of() : result;
    }

    private static List<Set<Literal>> intersect(List<Set<Literal>> a, List<Set<Literal>> b) {
        List<Set<Literal>> result = new ArrayList<>();
        for (Set<Literal> x : a) {
            for (Set<Literal> y : b) {
                Set<Literal> merged = new LinkedHashSet<>(x);
                merged.addAll(y);
                boolean contradiction = false;
                for (Literal lit : merged) {
                    if (merged.contains(lit.negate())) {
                        contradiction = true;
                        break;
                    }
                }
                if (!contradiction) {
                    result.add(merged);
                }
            }
        }
        return result;
    }

    private static List<Set<Literal>> simplify(List<Set<Literal>> dnf) {
        // Canonicalize each conjunction: sort literals, dedupe.
        List<Set<Literal>> sortedConjunctions = new ArrayList<>();
        for (Set<Literal> c : dnf) {
            Set<Literal> sorted = new TreeSet<>(Comparator.comparing(Literal::render));
            sorted.addAll(c);
            sortedConjunctions.add(sorted);
        }
        // Dedupe identical conjunctions.
        List<Set<Literal>> unique = new ArrayList<>();
        for (Set<Literal> c : sortedConjunctions) {
            boolean exists = false;
            for (Set<Literal> u : unique) {
                if (u.equals(c)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                unique.add(c);
            }
        }
        // Absorption: drop A if another conjunction B is a strict/equal subset of A.
        List<Set<Literal>> absorbed = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            boolean subsumed = false;
            for (int j = 0; j < unique.size(); j++) {
                if (i != j && unique.get(j).containsAll(unique.get(i))
                        && !unique.get(i).containsAll(unique.get(j))) {
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) {
                absorbed.add(unique.get(i));
            }
        }
        // Stable, deterministic ordering of conjunctions.
        absorbed.sort(Comparator.comparing(PlatformExpr::renderConjunction));
        return absorbed;
    }

    private static String renderConjunction(Set<Literal> c) {
        List<String> parts = new ArrayList<>();
        for (Literal l : c) {
            parts.add(l.render());
        }
        return String.join(" & ", parts);
    }

    private static String render(List<Set<Literal>> dnf) {
        if (dnf.isEmpty()) {
            return "false";
        }
        List<String> groups = new ArrayList<>();
        boolean disjunction = dnf.size() > 1;
        for (Set<Literal> c : dnf) {
            String inner = renderConjunction(c);
            groups.add(disjunction && c.size() > 1 ? "(" + inner + ")" : inner);
        }
        return String.join(" | ", groups);
    }
}
