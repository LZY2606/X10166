package lockmerge.lock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lockmerge.model.Diagnostic;
import lockmerge.model.EdgeRef;
import lockmerge.model.LockDocument;
import lockmerge.model.NodeKey;
import lockmerge.model.PackageNode;
import lockmerge.model.ParseResult;
import lockmerge.model.RootDep;
import lockmerge.platform.PlatformExpr;
import lockmerge.platform.PlatformSyntaxException;

/**
 * Parser for the lockfile v1 line-oriented format.
 *
 * <pre>
 * lockfile v1
 * root web@1.0.0
 * package lib@2.0.0
 *     source registry:acme/lib
 *     integrity sha256:hex...
 *     platforms linux | macos
 *     requires:
 *         dep@1.0.0 when arch=arm64
 *     end
 * end
 * </pre>
 *
 * Indentation is fixed at four spaces per block level; tabs are rejected.
 * The {@code requires:} block is closed by a single-indented {@code end};
 * a second single-indented {@code end} (or a following top-level line)
 * closes the package block.
 */
public final class LockParser {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+_-]*");
    private static final Pattern INTEGRITY =
            Pattern.compile("sha(?:256|384|512):[0-9a-fA-F]{4,}");
    private static final Pattern SOURCE_TYPE = Pattern.compile("[a-z][a-z0-9+.-]*");

    private static final int CTX_TOP = 0;
    private static final int CTX_PACKAGE = 1;
    private static final int CTX_REQUIRES = 2;

    private LockParser() {
    }

    public static ParseResult parse(String label, String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        List<Diagnostic> diagnostics = new ArrayList<>();
        String fingerprint = sha256(text.getBytes(StandardCharsets.UTF_8));

        List<RootDep> roots = new ArrayList<>();
        Map<NodeKey, PackageNode> nodes = new LinkedHashMap<>();

        int formatVersion = -1;
        int ctx = CTX_TOP;
        NodeBuilder current = null;
        boolean headerErrorEmitted = false;
        String[] lines = text.split("\n", -1);

        for (int idx = 0; idx < lines.length; idx++) {
            int lineNo = idx + 1;
            String rawLine = lines[idx];
            String trim = rawLine.trim();
            if (trim.isEmpty() || trim.startsWith("#")) {
                continue;
            }
            int indent = leadingSpaces(rawLine);
            if (indent != rawLine.length() - trim.length()) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "TAB_INDENT",
                        "indentation must use spaces (4 per level), tabs are not allowed"));
                continue;
            }
            if (indent % 4 != 0) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "BAD_INDENT",
                        "indentation must be a multiple of 4 spaces, found " + indent));
            }
            int level = indent / 4;

            try {
                switch (level) {
                    case 0 -> {
                        if (current != null) {
                            finishPackage(current, nodes, diagnostics, lineNo);
                            current = null;
                        }
                        ctx = CTX_TOP;
                        if (trim.equals("end")) {
                            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                                    lineNo, "UNEXPECTED_END",
                                    "'end' at top level has no open block"));
                            continue;
                        }
                        if (trim.startsWith("lockfile")) {
                            formatVersion = readHeader(trim, lineNo, diagnostics,
                                    formatVersion);
                        } else if (trim.startsWith("root ")) {
                            if (checkHeader(formatVersion, lineNo, diagnostics,
                                    headerErrorEmitted)) {
                                addRoot(trim.substring(5).trim(), lineNo, roots,
                                        diagnostics);
                            }
                            headerErrorEmitted = true;
                        } else if (trim.startsWith("package ")) {
                            if (checkHeader(formatVersion, lineNo, diagnostics,
                                    headerErrorEmitted)) {
                                String ref = trim.substring("package ".length()).trim();
                                current = startPackage(ref, lineNo, nodes, diagnostics);
                                ctx = current == null ? CTX_TOP : CTX_PACKAGE;
                            }
                            headerErrorEmitted = true;
                        } else {
                            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                                    lineNo, "UNKNOWN_DIRECTIVE",
                                    "unknown top-level directive: " + firstWord(trim)));
                        }
                    }
                    case 1 -> {
                        if (current == null) {
                            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                                    lineNo, "INDENT_WITHOUT_BLOCK",
                                    "indented line outside of a package block"));
                            continue;
                        }
                        if (trim.equals("end")) {
                            if (ctx == CTX_REQUIRES) {
                                // single canonical 'end' closes requires and package
                                finishPackage(current, nodes, diagnostics, lineNo);
                                current = null;
                                ctx = CTX_TOP;
                            } else {
                                finishPackage(current, nodes, diagnostics, lineNo);
                                current = null;
                                ctx = CTX_TOP;
                            }
                        } else if (trim.equals("requires:")) {
                            if (ctx == CTX_REQUIRES) {
                                diagnostics.add(new Diagnostic(
                                        Diagnostic.Severity.ERROR, lineNo,
                                        "NESTED_REQUIRES",
                                        "previous requires block is missing its 'end'"));
                            } else {
                                ctx = CTX_REQUIRES;
                            }
                        } else if (ctx == CTX_REQUIRES) {
                            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                                    lineNo, "MISSING_END",
                                    "requires block must be closed with 'end' before"
                                            + " package fields"));
                            ctx = CTX_PACKAGE;
                            readPackageField(trim, lineNo, current, diagnostics);
                        } else {
                            readPackageField(trim, lineNo, current, diagnostics);
                        }
                    }
                    default -> {
                        if (ctx != CTX_REQUIRES) {
                            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,
                                    lineNo, "UNEXPECTED_INDENT",
                                    "double-indented line is only valid inside"
                                            + " requires:"));
                            continue;
                        }
                        readRequires(trim, lineNo, current, diagnostics);
                    }
                }
            } catch (LineFailed ignored) {
                // diagnostic already recorded
            }
        }
        if (current != null) {
            finishPackage(current, nodes, diagnostics, lines.length);
        }
        if (formatVersion < 0) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, 1,
                    "MISSING_FORMAT",
                    "first non-comment line must be 'lockfile v1'"));
        }
        LockDocument document = new LockDocument(Math.max(formatVersion, 1), roots, nodes);
        return new ParseResult(label, text, fingerprint, document, diagnostics);
    }

    // ---- package assembly ---------------------------------------------

    private static final class NodeBuilder {
        NodeKey key;
        String source;
        String integrity;
        PlatformExpr platforms = PlatformExpr.always();
        List<EdgeRef> requires = new ArrayList<>();

        NodeBuilder(NodeKey key) {
            this.key = key;
        }
    }

    private static final class LineFailed extends RuntimeException {
        LineFailed() {
            super(null, null, true, false);
        }
    }

    private static int readHeader(String trim, int lineNo,
                                  List<Diagnostic> diagnostics, int seen) {
        if (seen >= 0) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "DUP_FORMAT", "duplicate format declaration"));
            return seen;
        }
        String rest = trim.substring("lockfile".length()).trim();
        if (!rest.startsWith("v")) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_FORMAT", "expected 'lockfile v1'"));
            return -1;
        }
        try {
            int version = Integer.parseInt(rest.substring(1));
            if (version != 1) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "UNSUPPORTED_FORMAT",
                        "only lockfile v1 is supported, found v" + version));
            }
            return version;
        } catch (NumberFormatException e) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_FORMAT", "expected 'lockfile v1'"));
            return -1;
        }
    }

    private static boolean checkHeader(int formatVersion, int lineNo,
                                       List<Diagnostic> diagnostics,
                                       boolean alreadyEmitted) {
        if (formatVersion >= 0) {
            return true;
        }
        if (!alreadyEmitted) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "FORMAT_ORDER",
                    "'lockfile v1' must appear before any root or package"
                            + " declarations"));
        }
        return false;
    }

    private static void addRoot(String body, int lineNo,
                                List<RootDep> roots, List<Diagnostic> diagnostics) {
        if (body.isEmpty()) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "EMPTY_ROOT", "root requires a name@version reference"));
            return;
        }
        NodeKey key = readReference(body, lineNo, diagnostics);
        if (key == null) {
            return;
        }
        RootDep existing = null;
        for (RootDep root : roots) {
            if (root.name().equals(key.name())) {
                existing = root;
                break;
            }
        }
        if (existing != null) {
            if (existing.pin().equals(key)) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, lineNo,
                        "DUP_ROOT",
                        "duplicate root declaration '" + key.reference() + "' ignored"));
            } else {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "ROOT_CONFLICT",
                        "root name '" + key.name() + "' pins both "
                                + existing.pin().reference() + " and "
                                + key.reference()));
            }
            return;
        }
        roots.add(new RootDep(key));
    }

    private static NodeBuilder startPackage(String ref, int lineNo,
                                            Map<NodeKey, PackageNode> nodes,
                                            List<Diagnostic> diagnostics) {
        NodeKey key = readReference(ref, lineNo, diagnostics);
        if (key == null) {
            return null;
        }
        if (nodes.containsKey(key)) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "DUP_PACKAGE",
                    "duplicate package definition for " + key.reference()));
            return null;
        }
        return new NodeBuilder(key);
    }

    private static void readPackageField(String trim, int lineNo, NodeBuilder current,
                                         List<Diagnostic> diagnostics) {
        if (current == null) {
            return;
        }
        if (trim.startsWith("source ")) {
            String value = trim.substring("source ".length()).trim();
            validateSource(value, lineNo, diagnostics);
            if (current.source != null) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "DUP_FIELD", "duplicate 'source' field"));
                return;
            }
            current.source = value;
        } else if (trim.startsWith("integrity ")) {
            String value = trim.substring("integrity ".length()).trim();
            if (!INTEGRITY.matcher(value).matches()) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "BAD_INTEGRITY",
                        "integrity must look like sha256:<hex> (also sha384/sha512),"
                                + " got '" + value + "'"));
                return;
            }
            if (current.integrity != null) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "DUP_FIELD", "duplicate 'integrity' field"));
                return;
            }
            current.integrity = value.toLowerCase();
        } else if (trim.startsWith("platforms ")) {
            String value = trim.substring("platforms ".length()).trim();
            andCondition(current, value, lineNo, diagnostics);
        } else {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "UNKNOWN_FIELD", "unknown package field: " + firstWord(trim)));
        }
    }

    private static void andCondition(NodeBuilder current, String raw, int lineNo,
                                     List<Diagnostic> diagnostics) {
        try {
            current.platforms = PlatformExpr.parse(
                    current.platforms.canonical() + " & (" + raw + ")");
        } catch (PlatformSyntaxException e) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_PLATFORM", e.getMessage()));
        }
    }

    private static void readRequires(String trim, int lineNo, NodeBuilder current,
                                     List<Diagnostic> diagnostics) {
        if (current == null) {
            return;
        }
        String refPart;
        String condition = null;
        int whenIdx = trim.indexOf(" when ");
        if (whenIdx >= 0) {
            refPart = trim.substring(0, whenIdx).trim();
            condition = trim.substring(whenIdx + " when ".length()).trim();
            if (condition.isEmpty()) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "EMPTY_WHEN",
                        "'when' must be followed by a platform expression"));
                return;
            }
        } else {
            refPart = trim;
        }
        NodeKey target = readReference(refPart, lineNo, diagnostics);
        if (target == null) {
            return;
        }
        String canonicalPlatforms = null;
        if (condition != null) {
            try {
                PlatformExpr parsed = PlatformExpr.parse(condition);
                if (parsed.isAlwaysTrue()) {
                    canonicalPlatforms = null;
                } else if (parsed.isAlwaysFalse()) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING,
                            lineNo, "IMPOSSIBLE_WHEN",
                            "condition '" + condition + "' normalizes to false"));
                } else {
                    canonicalPlatforms = parsed.canonical();
                }
            } catch (PlatformSyntaxException e) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                        "BAD_PLATFORM", e.getMessage()));
                return;
            }
        }
        EdgeRef edge = new EdgeRef(target, canonicalPlatforms);
        for (EdgeRef existing : current.requires) {
            if (existing.target().equals(target)) {
                if (ObjectsEquals(existing.platforms(), edge.platforms())) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING,
                            lineNo, "DUP_REQUIRES",
                            "duplicate edge to " + target.reference() + " ignored"));
                } else {
                    // multiple conditional edges to the same target are a
                    // guarded union; that is legal in lockfile v1
                    // (semantic equality is still checked per canonical edge)
                }
                current.requires.add(edge);
                return;
            }
        }
        current.requires.add(edge);
    }

    private static boolean ObjectsEquals(String a, String b) {
        String ca = a == null ? PlatformExpr.always().canonical()
                : PlatformExpr.parse(a).canonical();
        String cb = b == null ? PlatformExpr.always().canonical()
                : PlatformExpr.parse(b).canonical();
        return ca.equals(cb);
    }

    private static void finishPackage(NodeBuilder current,
                                      Map<NodeKey, PackageNode> nodes,
                                      List<Diagnostic> diagnostics, int lineNo) {
        if (current == null) {
            return;
        }
        if (current.source == null) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "MISSING_SOURCE",
                    "package " + current.key.reference() + " is missing 'source'"));
            current.source = "unknown:missing";
        }
        String platforms = current.platforms == null || current.platforms.isAlwaysTrue()
                ? null : current.platforms.canonical();
        nodes.put(current.key, new PackageNode(
                current.key, current.source, current.integrity,
                platforms, current.requires));
    }

    // ---- primitives ---------------------------------------------------

    static NodeKey readReference(String body, int lineNo,
                                 List<Diagnostic> diagnostics) {
        String ref = body.trim();
        int at = ref.indexOf('@');
        if (at <= 0) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_REFERENCE",
                    "'" + ref + "' must be name@version (name must be non-empty)"));
            return null;
        }
        if (at == ref.length() - 1) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_REFERENCE",
                    "'" + ref + "' is missing a version after '@'"));
            return null;
        }
        if (ref.indexOf('@', at + 1) >= 0) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_REFERENCE",
                    "'" + ref + "' contains more than one '@'"));
            return null;
        }
        String name = ref.substring(0, at);
        String version = ref.substring(at + 1);
        if (!NAME.matcher(name).matches()) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_NAME",
                    "invalid package name '" + name
                            + "' (allowed: letters, digits, _ . -)"));
            return null;
        }
        if (!VERSION.matcher(version).matches()) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_VERSION", "invalid version '" + version + "'"));
            return null;
        }
        return new NodeKey(name, version);
    }

    private static void validateSource(String value, int lineNo,
                                       List<Diagnostic> diagnostics) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_SOURCE",
                    "source '" + value + "' must be '<type>:<coordinate>'"));
            return;
        }
        String type = value.substring(0, colon);
        if (!SOURCE_TYPE.matcher(type).matches()) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_SOURCE",
                    "source type '" + type + "' must be lowercase letters/digits"));
            return;
        }
        if (value.substring(colon + 1).isBlank()) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, lineNo,
                    "BAD_SOURCE", "source coordinate must not be empty"));
        }
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String firstWord(String line) {
        int space = line.indexOf(' ');
        return space < 0 ? line : line.substring(0, space);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String fingerprintOf(String raw) {
        String text = raw == null ? ""
                : raw.replace("\r\n", "\n").replace('\r', '\n');
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
