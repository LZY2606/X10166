package mergeroom.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mergeroom.expr.PlatformExpr;
import mergeroom.model.Diagnostic;
import mergeroom.model.Edge;
import mergeroom.model.Lockfile;
import mergeroom.model.PackageNode;
import mergeroom.model.RootRef;

/**
 * Parser for the LFLOCK/1 text format.
 *
 * <pre>
 * # comment / blank lines
 * root "name" "version" [when "platform-expr"]
 * package "name" "version" source "coord" integrity "alg=hex" [when "expr"]
 *   require "child-name" "version" [when "expr"]
 * </pre>
 */
public final class LockfileParser {

    public static final String MAGIC = "# LFLOCK/1";

    private static final Set<String> DIGEST_ALGS = Set.of("sha1", "sha224", "sha256", "sha384", "sha512");

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<RootRef> roots = new ArrayList<>();
    private final Map<String, PackageNode> nodes = new LinkedHashMap<>();

    private static final class Line {
        final int no;
        final String text;

        Line(int no, String text) {
            this.no = no;
            this.text = text;
        }
    }

    private static final class Tokens {
        final Line line;
        final List<String> tokens = new ArrayList<>();
        final List<Integer> quotePos = new ArrayList<>();

        Tokens(Line line) {
            this.line = line;
        }
    }

    public Lockfile parse(String input) {
        diagnostics.clear();
        roots.clear();
        nodes.clear();
        if (input == null) {
            input = "";
        }
        PackageNode current = null;
        int currentStartLine = 0;
        List<Line> lines = splitLines(input);
        boolean magicSeen = false;
        for (Line line : lines) {
            String trimmed = stripComment(line.text).trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!magicSeen) {
                if (!trimmed.equals(MAGIC)) {
                    diagnostics.add(Diagnostic.error(line.no, "missing or invalid format header; expected '" + MAGIC + "'"));
                }
                magicSeen = true;
                if (!trimmed.equals(MAGIC)) {
                    // still continue parsing the line
                } else {
                    continue;
                }
            }
            Tokens t = tokenize(line, trimmed);
            if (t == null) {
                current = null;
                continue;
            }
            boolean indented = isIndented(line.text);
            String head = t.tokens.get(0);
            if (indented) {
                if ("require".equals(head)) {
                    parseRequire(t, current);
                } else {
                    diagnostics.add(Diagnostic.error(line.no, "indented line must be a 'require' clause"));
                    current = null;
                }
                continue;
            }
            switch (head) {
                case "root" -> current = parseRoot(t);
                case "package" -> current = parsePackage(t);
                default -> {
                    diagnostics.add(Diagnostic.error(line.no, "unknown directive '" + head + "'"));
                    current = null;
                }
            }
            currentStartLine = line.no;
        }
        if (!magicSeen) {
            diagnostics.add(Diagnostic.error(1, "empty input; expected '" + MAGIC + "' header"));
        }
        crossValidate();
        Lockfile lf = new Lockfile();
        lf.diagnostics().addAll(diagnostics);
        lf.roots().addAll(roots);
        lf.nodes().putAll(nodes);
        return lf;
    }

    private List<Line> splitLines(String input) {
        List<Line> result = new ArrayList<>();
        int lineNo = 1;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                result.add(new Line(lineNo, normalizeLineEnding(input.substring(start, i))));
                start = i + 1;
                lineNo++;
            }
        }
        result.add(new Line(lineNo, normalizeLineEnding(input.substring(start))));
        return result;
    }

    private static String normalizeLineEnding(String s) {
        if (s.endsWith("\r")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private boolean isIndented(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
            if (c == ' ') {
                return true;
            }
        }
        return false;
    }

    private String stripComment(String raw) {
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes) {
                return raw.substring(0, i);
            }
        }
        return raw;
    }

    private Tokens tokenize(Line line, String trimmed) {
        Tokens t = new Tokens(line);
        int i = 0;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < trimmed.length()) {
                    char q = trimmed.charAt(i);
                    if (q == '\\' && i + 1 < trimmed.length()) {
                        char n = trimmed.charAt(i + 1);
                        sb.append(switch (n) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case '"' -> '"';
                            case '\\' -> '\\';
                            default -> n;
                        });
                        i += 2;
                    } else if (q == '"') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        sb.append(q);
                        i++;
                    }
                }
                if (!closed) {
                    diagnostics.add(Diagnostic.error(line.no, "unterminated quoted string"));
                    return null;
                }
                t.tokens.add(sb.toString());
                t.quotePos.add(start);
            } else {
                while (i < trimmed.length() && !Character.isWhitespace(trimmed.charAt(i))) {
                    i++;
                }
                t.tokens.add(trimmed.substring(start, i));
            }
        }
        return t;
    }

    private PackageNode parseRoot(Tokens t) {
        Line line = t.line;
        // root "name" "version" [when "expr"]
        if (t.tokens.size() < 3) {
            diagnostics.add(Diagnostic.error(line.no, "root requires name and version"));
            return null;
        }
        String name = t.tokens.get(1);
        String version = t.tokens.get(2);
        String cond = "";
        int idx = 3;
        if (idx < t.tokens.size()) {
            if (!"when".equals(t.tokens.get(idx))) {
                diagnostics.add(Diagnostic.error(line.no, "expected 'when' after root version"));
                return null;
            }
            cond = parseCondition(t, idx + 1, "root " + name);
            if (cond == null) {
                return null;
            }
            idx += 2;
        }
        if (idx != t.tokens.size()) {
            diagnostics.add(Diagnostic.error(line.no, "trailing tokens in root declaration"));
            return null;
        }
        if (name.isEmpty() || version.isEmpty()) {
            diagnostics.add(Diagnostic.error(line.no, "root name and version must not be empty"));
            return null;
        }
        for (RootRef r : roots) {
            if (r.name().equals(name)) {
                diagnostics.add(Diagnostic.error(line.no, "duplicate root declaration '" + name + "'"));
                return null;
            }
        }
        roots.add(new RootRef(name, version, cond));
        return null;
    }

    private PackageNode parsePackage(Tokens t) {
        Line line = t.line;
        // package "name" "version" source "coord" integrity "digest" [when "expr"]
        if (t.tokens.size() < 7) {
            diagnostics.add(Diagnostic.error(line.no,
                    "package requires name, version, source and integrity"));
            return null;
        }
        String name = t.tokens.get(1);
        String version = t.tokens.get(2);
        if (!"source".equals(t.tokens.get(3))) {
            diagnostics.add(Diagnostic.error(line.no, "expected 'source' keyword"));
            return null;
        }
        String source = t.tokens.get(4);
        if (!"integrity".equals(t.tokens.get(5))) {
            diagnostics.add(Diagnostic.error(line.no, "expected 'integrity' keyword"));
            return null;
        }
        String integrity = t.tokens.get(6);
        String cond = "";
        int idx = 7;
        if (idx < t.tokens.size()) {
            if (!"when".equals(t.tokens.get(idx))) {
                diagnostics.add(Diagnostic.error(line.no, "expected 'when' keyword"));
                return null;
            }
            cond = parseCondition(t, idx + 1, "package " + name);
            if (cond == null) {
                return null;
            }
            idx += 2;
        }
        if (idx != t.tokens.size()) {
            diagnostics.add(Diagnostic.error(line.no, "trailing tokens in package declaration"));
            return null;
        }
        if (name.isEmpty() || version.isEmpty()) {
            diagnostics.add(Diagnostic.error(line.no, "package name and version must not be empty"));
            return null;
        }
        if (!isValidDigest(integrity)) {
            diagnostics.add(Diagnostic.warning(line.no,
                    "integrity digest should look like 'alg=hex' (got '" + integrity + "')"));
        }
        String key = name + "@" + version;
        PackageNode node = new PackageNode(name, version, source, integrity, cond, new LinkedHashMap<>());
        if (nodes.putIfAbsent(key, node) != null) {
            diagnostics.add(Diagnostic.error(line.no, "duplicate package node '" + key + "'"));
        }
        return node;
    }

    private void parseRequire(Tokens t, PackageNode owner) {
        Line line = t.line;
        if (owner == null) {
            diagnostics.add(Diagnostic.error(line.no, "'require' outside of a package block"));
            return;
        }
        // require "child" "version" [when "expr"]
        if (t.tokens.size() < 3) {
            diagnostics.add(Diagnostic.error(line.no, "require needs child name and version"));
            return;
        }
        String child = t.tokens.get(1);
        String spec = t.tokens.get(2);
        String cond = "";
        int idx = 3;
        if (idx < t.tokens.size()) {
            if (!"when".equals(t.tokens.get(idx))) {
                diagnostics.add(Diagnostic.error(line.no, "expected 'when' after require version"));
                return;
            }
            cond = parseCondition(t, idx + 1, "require " + child);
            if (cond == null) {
                return;
            }
            idx += 2;
        }
        if (idx != t.tokens.size()) {
            diagnostics.add(Diagnostic.error(line.no, "trailing tokens in require clause"));
            return;
        }
        if (child.isEmpty() || spec.isEmpty()) {
            diagnostics.add(Diagnostic.error(line.no, "require child and version must not be empty"));
            return;
        }
        if (owner.edges().putIfAbsent(child, new Edge(child, spec, cond)) != null) {
            diagnostics.add(Diagnostic.error(line.no,
                    "duplicate require on '" + child + "' in package '" + owner.key() + "'"));
        }
    }

    private String parseCondition(Tokens t, int valueIndex, String where) {
        if (valueIndex >= t.tokens.size()) {
            diagnostics.add(Diagnostic.error(t.line.no, "missing platform expression after 'when' in " + where));
            return null;
        }
        String raw = t.tokens.get(valueIndex);
        if (valueIndex + 1 != t.tokens.size()) {
            diagnostics.add(Diagnostic.error(t.line.no, "trailing tokens after platform expression in " + where));
            return null;
        }
        try {
            return PlatformExpr.parse(raw).canonical();
        } catch (PlatformExpr.ExprParseException e) {
            diagnostics.add(Diagnostic.error(t.line.no,
                    "invalid platform expression '" + raw + "' in " + where + ": " + e.getMessage()));
            return null;
        }
    }

    private boolean isValidDigest(String integrity) {
        int eq = integrity.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String alg = integrity.substring(0, eq).toLowerCase();
        String hex = integrity.substring(eq + 1);
        if (!DIGEST_ALGS.contains(alg) || hex.isEmpty()) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean hexChar = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hexChar) {
                return false;
            }
        }
        return true;
    }

    private void crossValidate() {
        for (RootRef r : roots) {
            if (!nodes.containsKey(r.targetKey())) {
                diagnostics.add(Diagnostic.error(0,
                        "root '" + r.name() + "' points at missing node '" + r.targetKey() + "'"));
            }
        }
        for (PackageNode node : nodes.values()) {
            for (Edge edge : node.edges().values()) {
                String target = edge.child() + "@" + edge.spec();
                if (!nodes.containsKey(target)) {
                    diagnostics.add(Diagnostic.error(0,
                            "package '" + node.key() + "' references missing '" + target + "'"));
                }
            }
        }
        // Integrity conflicts: same source coordinate + version must agree on digest.
        record SV(String source, String version) {}
        Map<SV, String> digestBy = new LinkedHashMap<>();
        Map<SV, String> whoBy = new LinkedHashMap<>();
        for (PackageNode node : nodes.values()) {
            SV sv = new SV(node.source(), node.version());
            String existing = digestBy.get(sv);
            if (existing == null) {
                digestBy.put(sv, node.integrity());
                whoBy.put(sv, node.name());
            } else if (!existing.equals(node.integrity())) {
                diagnostics.add(Diagnostic.error(0,
                        "integrity conflict for source '" + node.source() + "' version '" + node.version()
                                + "': '" + whoBy.get(sv) + "' has '" + existing
                                + "' but '" + node.name() + "' has '" + node.integrity() + "'"));
            }
        }
    }
}
