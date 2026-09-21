package dev.lockmerge.lock;

import dev.lockmerge.model.Diagnostic;
import dev.lockmerge.model.LockDocument;
import dev.lockmerge.model.Node;
import dev.lockmerge.model.Ref;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser for the custom "Semantic Lock" format (version 1).
 *
 * <pre>
 * lockfile v1
 * root acme@1.0.0
 * root util@2.0.0 [os=linux | os=darwin]
 *
 * package acme@1.0.0
 *   source registry:https://repo.example.com/acme-1.0.0.tgz
 *   integrity sha256:9f2a...
 *   platform os=linux & arch=aarch64
 *   child util@2.0.0
 *   child lib@3.1.0 [!os=windows]
 * </pre>
 *
 * Indentation is two spaces per level; lines beginning with '#' outside of
 * brackets and blank lines are ignored. Conditions are stored normalized.
 */
public final class LockParser {

    private LockParser() {
    }

    public static final class Result {
        public final LockDocument document;
        public final List<Diagnostic> diagnostics;

        Result(LockDocument document, List<Diagnostic> diagnostics) {
            this.document = document;
            this.diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    public static Result parse(String text) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Ref> roots = new ArrayList<>();
        List<NodeBuilder> builders = new ArrayList<>();

        String[] rawLines = text.replace("\r\n", "\n").split("\n", -1);
        boolean headerSeen = false;
        NodeBuilder current = null;
        Set<String> nodeIds = new HashSet<>();
        Set<String> rootKeys = new HashSet<>();

        for (int lineNo0 = 0; lineNo0 < rawLines.length; lineNo0++) {
            int lineNo = lineNo0 + 1;
            String stripped = stripComment(rawLines[lineNo0]);
            if (stripped.isBlank()) {
                continue;
            }
            int indent = countIndent(stripped);
            String content = stripped.substring(indent);
            int level = indent / 2;

            if (indent % 2 != 0) {
                diagnostics.add(Diagnostic.error(lineNo, "INDENT",
                        "indentation must be multiples of two spaces"));
                continue;
            }

            if (level == 0) {
                if (!headerSeen) {
                    if (!content.equals("lockfile v1")) {
                        diagnostics.add(Diagnostic.error(lineNo, "HEADER",
                                "first directive must be 'lockfile v1'"));
                        return new Result(null, diagnostics);
                    }
                    headerSeen = true;
                    continue;
                }
                if (content.startsWith("root ")) {
                    current = null;
                    Ref ref = parseRef(content.substring(5).trim(), lineNo, diagnostics);
                    if (ref != null && rootKeys.add(ref.key())) {
                        roots.add(ref);
                    } else if (ref != null) {
                        diagnostics.add(Diagnostic.error(lineNo, "DUP_ROOT",
                                "duplicate root '" + ref.key() + "'"));
                    }
                } else if (content.startsWith("package ")) {
                    String coord = content.substring(8).trim();
                    String[] parts = splitCoord(coord);
                    if (parts == null) {
                        current = null;
                        diagnostics.add(Diagnostic.error(lineNo, "COORD",
                                "package coordinate must be 'name@version': " + coord));
                    } else if (!nodeIds.add(parts[0] + "@" + parts[1])) {
                        current = null;
                        diagnostics.add(Diagnostic.error(lineNo, "DUP_PACKAGE",
                                "duplicate package '" + coord + "'"));
                    } else {
                        current = new NodeBuilder(parts[0], parts[1], lineNo);
                        builders.add(current);
                    }
                } else {
                    current = null;
                    diagnostics.add(Diagnostic.error(lineNo, "DIRECTIVE",
                            "unknown top-level directive: " + content));
                }
            } else {
                if (!headerSeen) {
                    diagnostics.add(Diagnostic.error(lineNo, "HEADER",
                            "'lockfile v1' header must precede content"));
                    continue;
                }
                if (current == null) {
                    diagnostics.add(Diagnostic.error(lineNo, "ORPHAN",
                            "indented line outside of a package block"));
                    continue;
                }
                if (level != 1) {
                    diagnostics.add(Diagnostic.error(lineNo, "INDENT",
                            "package fields must use exactly two spaces of indentation"));
                    continue;
                }
                current.parseField(content, lineNo, diagnostics);
            }
        }

        if (!headerSeen && diagnostics.stream().noneMatch(d -> "HEADER".equals(d.code()))) {
            diagnostics.add(Diagnostic.error(1, "HEADER", "missing 'lockfile v1' header"));
        }

        List<Node> nodes = new ArrayList<>();
        for (NodeBuilder builder : builders) {
            nodes.add(builder.build(diagnostics));
        }
        return new Result(new LockDocument(roots, nodes), diagnostics);
    }

    private static String stripComment(String line) {
        int depth = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']' && depth > 0) {
                depth--;
            } else if (ch == '#' && depth == 0) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int countIndent(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    static Ref parseRef(String text, int lineNo, List<Diagnostic> diagnostics) {
        String coord;
        String condition = null;
        int bracket = text.indexOf('[');
        if (bracket >= 0) {
            if (!text.endsWith("]")) {
                diagnostics.add(Diagnostic.error(lineNo, "REF",
                        "reference condition must end with ']': " + text));
                return null;
            }
            coord = text.substring(0, bracket).trim();
            String rawCondition = text.substring(bracket + 1, text.length() - 1).trim();
            try {
                condition = PlatformExpr.canonical(rawCondition);
                if (condition == null) {
                    diagnostics.add(Diagnostic.error(lineNo, "REF",
                            "empty platform condition in: " + text));
                    return null;
                }
            } catch (PlatformExpr.SyntaxException ex) {
                diagnostics.add(Diagnostic.error(lineNo, "REF",
                        "invalid platform condition '" + rawCondition + "': " + ex.getMessage()));
                return null;
            }
        } else {
            coord = text.trim();
        }
        String[] parts = splitCoord(coord);
        if (parts == null) {
            diagnostics.add(Diagnostic.error(lineNo, "REF",
                    "reference must be 'name@version': " + text));
            return null;
        }
        return new Ref(parts[0], parts[1], condition);
    }

    private static String[] splitCoord(String coord) {
        int at = coord.lastIndexOf('@');
        if (at <= 0 || at == coord.length() - 1) {
            return null;
        }
        return new String[] { coord.substring(0, at), coord.substring(at + 1) };
    }

    private static final class NodeBuilder {
        private final String name;
        private final String version;
        private final int lineNo;
        private String source;
        private String integrity;
        private String platformCanonical;
        private final List<Ref> children = new ArrayList<>();
        private final Set<String> childKeys = new HashSet<>();

        NodeBuilder(String name, String version, int lineNo) {
            this.name = name;
            this.version = version;
            this.lineNo = lineNo;
        }

        String id() {
            return name + "@" + version;
        }

        void parseField(String content, int lineNo, List<Diagnostic> diagnostics) {
            if (content.startsWith("source ")) {
                String value = content.substring(7).trim();
                if (value.isEmpty()) {
                    diagnostics.add(Diagnostic.error(lineNo, "SOURCE", "empty source"));
                } else if (source != null) {
                    diagnostics.add(Diagnostic.error(lineNo, "DUP_FIELD",
                            "duplicate 'source' in package " + id()));
                } else {
                    source = value;
                }
            } else if (content.startsWith("integrity ")) {
                String value = content.substring(10).trim();
                String hex = value.startsWith("sha256:") ? value.substring(7) : null;
                if (hex == null || !hex.matches("[0-9a-f]{64}")) {
                    diagnostics.add(Diagnostic.error(lineNo, "INTEGRITY",
                            "integrity must be 'sha256:' plus 64 lowercase hex chars for "
                                    + id()));
                } else if (integrity != null) {
                    diagnostics.add(Diagnostic.error(lineNo, "DUP_FIELD",
                            "duplicate 'integrity' in package " + id()));
                } else {
                    integrity = value;
                }
            } else if (content.startsWith("platform ")) {
                String value = content.substring(9).trim();
                try {
                    if (platformCanonical != null) {
                        diagnostics.add(Diagnostic.error(lineNo, "DUP_FIELD",
                                "duplicate 'platform' in package " + id()));
                        return;
                    }
                    platformCanonical = PlatformExpr.canonical(value);
                } catch (PlatformExpr.SyntaxException ex) {
                    diagnostics.add(Diagnostic.error(lineNo, "PLATFORM",
                            "invalid platform expression for " + id() + ": " + ex.getMessage()));
                }
            } else if (content.startsWith("child ")) {
                Ref ref = parseRef(content.substring(6).trim(), lineNo, diagnostics);
                if (ref != null) {
                    if (childKeys.add(ref.key())) {
                        children.add(ref);
                    } else {
                        diagnostics.add(Diagnostic.error(lineNo, "DUP_CHILD",
                                "duplicate child '" + ref.key() + "' in " + id()));
                    }
                }
            } else {
                diagnostics.add(Diagnostic.error(lineNo, "FIELD",
                        "unknown package field: " + content));
            }
        }

        Node build(List<Diagnostic> diagnostics) {
            if (source == null) {
                diagnostics.add(Diagnostic.error(lineNo, "SOURCE",
                        "package " + id() + " is missing 'source'"));
            }
            if (integrity == null) {
                diagnostics.add(Diagnostic.error(lineNo, "INTEGRITY",
                        "package " + id() + " is missing 'integrity'"));
            }
            return new Node(name, version,
                    source == null ? "" : source,
                    integrity == null ? "" : integrity,
                    platformCanonical,
                    new ArrayList<>(children));
        }
    }
}
