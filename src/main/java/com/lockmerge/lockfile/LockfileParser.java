package com.lockmerge.lockfile;

import com.lockmerge.lockfile.Model.Diagnostic;
import com.lockmerge.lockfile.Model.Lockfile;
import com.lockmerge.lockfile.Model.NameVersion;
import com.lockmerge.lockfile.Model.PackageNode;
import com.lockmerge.lockfile.Model.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the custom lockfile format:
 *
 * <pre>
 * lockfile v1
 *
 * root app@1.0.0
 *
 * package app@1.0.0 {
 *   source registry:https://pkgs.example.com/app
 *   integrity sha256-deadbeef
 *   platform os == "linux" &amp;&amp; arch == "x64"
 *   depends lib@2.0.0
 * }
 * </pre>
 */
public final class LockfileParser {

    public record ParseResult(Lockfile lockfile, List<Diagnostic> diagnostics) {
        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
        }
    }

    private enum TokKind { WORD, STRING, LBRACE, RBRACE, LPAREN, RPAREN, AND, OR, NOT, EQ, NE, EOF }

    private record Tok(TokKind kind, String text, int line) {}

    private static final class Lexer {
        private final String src;
        private int pos;
        private int line = 1;

        Lexer(String src) { this.src = src; }

        List<Tok> tokenize() {
            List<Tok> out = new ArrayList<>();
            while (true) {
                skipWsAndComments();
                if (pos >= src.length()) {
                    out.add(new Tok(TokKind.EOF, "", line));
                    return out;
                }
                char c = src.charAt(pos);
                int tokLine = line;
                switch (c) {
                    case '{' -> { pos++; out.add(new Tok(TokKind.LBRACE, "{", tokLine)); }
                    case '}' -> { pos++; out.add(new Tok(TokKind.RBRACE, "}", tokLine)); }
                    case '(' -> { pos++; out.add(new Tok(TokKind.LPAREN, "(", tokLine)); }
                    case ')' -> { pos++; out.add(new Tok(TokKind.RPAREN, ")", tokLine)); }
                    case '!' -> {
                        if (peek('=')) { pos += 2; out.add(new Tok(TokKind.NE, "!=", tokLine)); }
                        else { pos++; out.add(new Tok(TokKind.NOT, "!", tokLine)); }
                    }
                    case '=' -> {
                        if (peek('=')) { pos += 2; out.add(new Tok(TokKind.EQ, "==", tokLine)); }
                        else throw error("unexpected '=', did you mean '=='?", tokLine);
                    }
                    case '&' -> {
                        if (peek('&')) { pos += 2; out.add(new Tok(TokKind.AND, "&&", tokLine)); }
                        else throw error("unexpected '&', did you mean '&&'?", tokLine);
                    }
                    case '|' -> {
                        if (peek('|')) { pos += 2; out.add(new Tok(TokKind.OR, "||", tokLine)); }
                        else throw error("unexpected '|', did you mean '||'?", tokLine);
                    }
                    case '"' -> out.add(new Tok(TokKind.STRING, readString(tokLine), tokLine));
                    default -> out.add(new Tok(TokKind.WORD, readWord(), tokLine));
                }
            }
        }

        private boolean peek(char expected) {
            return pos + 1 < src.length() && src.charAt(pos + 1) == expected;
        }

        private void skipWsAndComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\n') { line++; pos++; }
                else if (Character.isWhitespace(c)) pos++;
                else if (c == '#') {
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                } else return;
            }
        }

        private String readWord() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c) || c == '#' || c == '{' || c == '}'
                        || c == '(' || c == ')' || c == '"' || c == '!' || c == '=' || c == '&' || c == '|') {
                    break;
                }
                pos++;
            }
            return src.substring(start, pos);
        }

        private String readString(int tokLine) {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != '"') {
                char c = src.charAt(pos);
                if (c == '\n') throw error("unterminated string literal", tokLine);
                if (c == '\\' && pos + 1 < src.length()) {
                    char n = src.charAt(pos + 1);
                    if (n == '"' || n == '\\') { sb.append(n); pos += 2; continue; }
                }
                sb.append(c);
                pos++;
            }
            if (pos >= src.length()) throw error("unterminated string literal", tokLine);
            pos++; // closing quote
            return sb.toString();
        }

        private IllegalArgumentException error(String msg, int atLine) {
            return new IllegalArgumentException("line " + atLine + ": " + msg);
        }
    }

    private final List<Tok> toks;
    private int idx;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Lockfile lockfile = new Lockfile();

    private LockfileParser(List<Tok> toks) { this.toks = toks; }

    public static ParseResult parse(String text) {
        List<Tok> toks;
        List<Diagnostic> lexErrors = new ArrayList<>();
        try {
            toks = new Lexer(text).tokenize();
        } catch (IllegalArgumentException e) {
            lexErrors.add(new Diagnostic(Severity.ERROR, 0, e.getMessage()));
            return new ParseResult(new Lockfile(), lexErrors);
        }
        LockfileParser p = new LockfileParser(toks);
        p.run();
        return new ParseResult(p.lockfile, p.diagnostics);
    }

    private Tok peek() { return toks.get(Math.min(idx, toks.size() - 1)); }

    private Tok next() { return toks.get(Math.min(idx++, toks.size() - 1)); }

    private boolean at(TokKind kind) { return peek().kind == kind; }

    private boolean atWord(String w) { return peek().kind == TokKind.WORD && peek().text.equals(w); }

    private void error(String msg, Tok at) {
        diagnostics.add(new Diagnostic(Severity.ERROR, at.line, msg));
    }

    private void run() {
        // header
        if (atWord("lockfile")) {
            next();
            if (at(TokKind.WORD)) {
                Tok v = next();
                if (!v.text.equals("v1")) error("unsupported lockfile version '" + v.text + "'", v);
            } else {
                error("expected version after 'lockfile'", peek());
            }
        } else {
            error("missing 'lockfile v1' header", peek());
        }

        while (!at(TokKind.EOF)) {
            if (atWord("root")) {
                next();
                parseRoot();
            } else if (atWord("package")) {
                next();
                parsePackage();
            } else {
                error("unexpected token '" + peek().text + "'", peek());
                next();
            }
        }

        // graph-level parse warnings
        for (NameVersion root : lockfile.roots) {
            if (!lockfile.packages.containsKey(root)) {
                diagnostics.add(new Diagnostic(Severity.WARNING, 0,
                        "root " + root + " has no matching package definition"));
            }
        }
        for (PackageNode node : lockfile.packages.values()) {
            for (NameVersion dep : node.depends) {
                if (!lockfile.packages.containsKey(dep)) {
                    diagnostics.add(new Diagnostic(Severity.WARNING, 0,
                            "package " + node.key() + " depends on undefined package " + dep));
                }
            }
        }
    }

    private void parseRoot() {
        if (!at(TokKind.WORD)) { error("expected name@version after 'root'", peek()); return; }
        Tok t = next();
        try {
            lockfile.roots.add(NameVersion.parse(t.text));
        } catch (IllegalArgumentException e) {
            error(e.getMessage(), t);
        }
    }

    private void parsePackage() {
        if (!at(TokKind.WORD)) { error("expected name@version after 'package'", peek()); return; }
        Tok nameTok = next();
        NameVersion key;
        try {
            key = NameVersion.parse(nameTok.text);
        } catch (IllegalArgumentException e) {
            error(e.getMessage(), nameTok);
            key = new NameVersion("invalid", "invalid");
        }
        if (!at(TokKind.LBRACE)) { error("expected '{' after package name", peek()); return; }
        next();

        PackageNode node = new PackageNode(key.name(), key.version());
        boolean dup = lockfile.packages.containsKey(key);
        if (dup) {
            error("duplicate package definition " + key, nameTok);
        } else {
            lockfile.packages.put(key, node);
        }

        while (!at(TokKind.RBRACE) && !at(TokKind.EOF)) {
            Tok field = peek();
            if (atWord("source")) {
                next();
                node.source = expectWord("source coordinate");
            } else if (atWord("integrity")) {
                next();
                node.integrity = expectWord("integrity digest");
            } else if (atWord("platform")) {
                next();
                PlatformExpr expr = parsePlatformExpr();
                if (expr != null) node.platform = expr;
            } else if (atWord("depends")) {
                next();
                String nv = expectWord("name@version");
                if (nv != null) {
                    try {
                        node.depends.add(NameVersion.parse(nv));
                    } catch (IllegalArgumentException e) {
                        error(e.getMessage(), field);
                    }
                }
            } else {
                error("unknown package field '" + peek().text + "'", peek());
                next();
            }
        }
        if (at(TokKind.RBRACE)) next();
        else error("missing '}' for package " + key, peek());
        if (node.source == null) {
            diagnostics.add(new Diagnostic(Severity.WARNING, nameTok.line,
                    "package " + key + " has no source"));
        }
        if (node.integrity == null) {
            diagnostics.add(new Diagnostic(Severity.WARNING, nameTok.line,
                    "package " + key + " has no integrity digest"));
        }
    }

    private String expectWord(String what) {
        if (at(TokKind.WORD)) return next().text;
        error("expected " + what, peek());
        return null;
    }

    private PlatformExpr parsePlatformExpr() {
        try {
            return parseOr();
        } catch (IllegalArgumentException e) {
            diagnostics.add(new Diagnostic(Severity.ERROR, peek().line, "platform: " + e.getMessage()));
            return null;
        }
    }

    private PlatformExpr parseOr() {
        List<PlatformExpr> parts = new ArrayList<>();
        parts.add(parseAnd());
        while (at(TokKind.OR)) { next(); parts.add(parseAnd()); }
        return parts.size() == 1 ? parts.get(0) : new PlatformExpr.Or(parts);
    }

    private PlatformExpr parseAnd() {
        List<PlatformExpr> parts = new ArrayList<>();
        parts.add(parseUnary());
        while (at(TokKind.AND)) { next(); parts.add(parseUnary()); }
        return parts.size() == 1 ? parts.get(0) : new PlatformExpr.And(parts);
    }

    private PlatformExpr parseUnary() {
        if (at(TokKind.NOT)) { next(); return new PlatformExpr.Not(parseUnary()); }
        if (at(TokKind.LPAREN)) {
            next();
            PlatformExpr inner = parseOr();
            if (at(TokKind.RPAREN)) next();
            else throw new IllegalArgumentException("missing ')'");
            return inner;
        }
        if (atWord("true")) { next(); return new PlatformExpr.True(); }
        if (atWord("false")) { next(); return new PlatformExpr.False(); }
        // comparison: WORD (==|!=) STRING
        if (at(TokKind.WORD)) {
            Tok key = next();
            PlatformExpr.Atom.Op op;
            if (at(TokKind.EQ)) { next(); op = PlatformExpr.Atom.Op.EQ; }
            else if (at(TokKind.NE)) { next(); op = PlatformExpr.Atom.Op.NE; }
            else throw new IllegalArgumentException("expected '==' or '!=' after '" + key.text + "'");
            if (!at(TokKind.STRING)) throw new IllegalArgumentException("expected quoted string value");
            Tok value = next();
            return new PlatformExpr.Atom(key.text, op, value.text);
        }
        throw new IllegalArgumentException("unexpected token '" + peek().text + "'");
    }
}
