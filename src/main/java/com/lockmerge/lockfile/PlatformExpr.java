package com.lockmerge.lockfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Boolean expression over platform atoms such as {@code os == "linux"}.
 * Expressions are compared by a normalized canonical DNF form, never by raw text.
 */
public sealed interface PlatformExpr {

    /** Canonical normalized string (DNF, sorted atoms/terms). */
    default String canonical() {
        return Dnf.canonicalString(Dnf.of(this));
    }

    default boolean equivalentTo(PlatformExpr other) {
        return canonical().equals(other.canonical());
    }

    record Atom(String key, Op op, String value) implements PlatformExpr {
        public enum Op { EQ, NE }

        Atom negate() {
            return new Atom(key, op == Op.EQ ? Op.NE : Op.EQ, value);
        }

        String atomString() {
            return key + (op == Op.EQ ? "==" : "!=") + "\"" + value + "\"";
        }
    }

    record True() implements PlatformExpr {}
    record False() implements PlatformExpr {}
    record Not(PlatformExpr inner) implements PlatformExpr {}
    record And(List<PlatformExpr> parts) implements PlatformExpr {}
    record Or(List<PlatformExpr> parts) implements PlatformExpr {}

    /** DNF: set of conjunctions, each a set of signed atoms. Empty conjunction = true; empty DNF = false. */
    final class Dnf {
        private Dnf() {}

        static Set<Set<Atom>> of(PlatformExpr expr) {
            return dnf(nnf(expr, false));
        }

        /** Negation-normal form: push Not down to atoms via De Morgan. */
        static PlatformExpr nnf(PlatformExpr expr, boolean neg) {
            if (expr instanceof Atom a) return neg ? a.negate() : a;
            if (expr instanceof True) return neg ? new False() : expr;
            if (expr instanceof False) return neg ? new True() : expr;
            if (expr instanceof Not n) return nnf(n.inner(), !neg);
            if (expr instanceof And a) {
                List<PlatformExpr> parts = new ArrayList<>();
                for (PlatformExpr p : a.parts()) parts.add(nnf(p, neg));
                return neg ? new Or(parts) : new And(parts);
            }
            if (expr instanceof Or o) {
                List<PlatformExpr> parts = new ArrayList<>();
                for (PlatformExpr p : o.parts()) parts.add(nnf(p, neg));
                return neg ? new And(parts) : new Or(parts);
            }
            throw new IllegalStateException("unknown expr " + expr);
        }

        static Set<Set<Atom>> dnf(PlatformExpr expr) {
            Set<Set<Atom>> out = new LinkedHashSet<>();
            if (expr instanceof True) {
                out.add(new TreeSet<>(Comparator.comparing(Atom::atomString)));
                return out;
            }
            if (expr instanceof False) {
                return out;
            }
            if (expr instanceof Atom a) {
                Set<Atom> term = new TreeSet<>(Comparator.comparing(Atom::atomString));
                term.add(a);
                out.add(term);
                return out;
            }
            if (expr instanceof Or o) {
                for (PlatformExpr p : o.parts()) out.addAll(dnf(p));
                return out;
            }
            if (expr instanceof And a) {
                Set<Set<Atom>> acc = new LinkedHashSet<>();
                acc.add(new TreeSet<>(Comparator.comparing(Atom::atomString)));
                for (PlatformExpr p : a.parts()) {
                    Set<Set<Atom>> rhs = dnf(p);
                    Set<Set<Atom>> next = new LinkedHashSet<>();
                    for (Set<Atom> x : acc) {
                        for (Set<Atom> y : rhs) {
                            Set<Atom> merged = new TreeSet<>(Comparator.comparing(Atom::atomString));
                            merged.addAll(x);
                            merged.addAll(y);
                            next.add(merged);
                        }
                    }
                    acc = next;
                }
                return acc;
            }
            throw new IllegalStateException("not in NNF: " + expr);
        }

        static String canonicalString(Set<Set<Atom>> dnf) {
            // Drop contradictory terms (contain both k=="v" and k!="v").
            List<Set<Atom>> terms = new ArrayList<>();
            for (Set<Atom> term : dnf) {
                boolean contradiction = false;
                for (Atom a : term) {
                    Atom flip = a.negate();
                    if (term.contains(flip)) { contradiction = true; break; }
                }
                if (!contradiction) terms.add(term);
            }
            if (terms.isEmpty()) return "false";
            List<String> rendered = new ArrayList<>();
            for (Set<Atom> term : terms) {
                if (term.isEmpty()) return "true"; // empty conjunction absorbs
                List<String> atoms = new ArrayList<>();
                for (Atom a : term) atoms.add(a.atomString());
                rendered.add(String.join(" && ", atoms));
            }
            rendered.sort(String::compareTo);
            List<String> dedup = new ArrayList<>(new LinkedHashSet<>(rendered));
            return String.join(" || ", dedup);
        }
    }
}
