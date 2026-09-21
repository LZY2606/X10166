package lockmerge.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import lockmerge.model.Keys;
import lockmerge.model.LockGraph;
import lockmerge.model.PackageNode;
import lockmerge.model.PlatformExpr;

/**
 * 自定义锁定格式解析器。文法：
 *   file    := "lockfile" NUMBER (rootDecl | packageDecl)*
 *   rootDecl:= "root" STRING                // "name@version"
 *   packageDecl := "package" STRING "{" field* "}"
 *   field   := "source" STRING | "integrity" STRING
 *            | "platform" STRING | "dep" STRING
 * 注释以 # 开头直到行尾。
 */
public final class LockfileParser {

    public static ParseResult parse(String text) {
        return new LockfileParser(text).run();
    }

    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int pos;

    private LockfileParser(String text) {
        this.tokens = lex(text);
    }

    private ParseResult run() {
        List<String> roots = new ArrayList<>();
        TreeMap<String, PackageNode> packages = new TreeMap<>();

        Token t = peek();
        if (!t.isIdent("lockfile")) {
            error(t, "文件必须以 'lockfile 1' 开头");
            return finish(null, roots, packages);
        }
        next();
        Token ver = peek();
        if (ver.kind != Kind.NUMBER || !ver.text.equals("1")) {
            error(ver, "不支持的格式版本，期望 'lockfile 1'");
        } else {
            next();
        }

        while (!atEnd()) {
            t = peek();
            if (t.isIdent("root")) {
                next();
                String ref = expectString("root 后需要 \"name@version\" 引用");
                if (ref != null) {
                    String key = checkRef(ref, t.line);
                    if (key != null) {
                        if (roots.contains(key)) {
                            error(t, "重复的根依赖: " + key);
                        } else {
                            roots.add(key);
                        }
                    }
                }
            } else if (t.isIdent("package")) {
                next();
                parsePackage(packages);
            } else {
                error(t, "无法识别的顶层声明: " + t.text);
                next();
            }
        }

        // 引用完整性校验：根与子引用必须指向已声明的包
        for (String root : roots) {
            if (!packages.containsKey(root)) {
                diagnostics.add(Diagnostic.error(0, "根依赖悬空引用: " + root + " 没有对应的 package 声明"));
            }
        }
        for (PackageNode node : packages.values()) {
            for (String dep : node.deps) {
                if (!packages.containsKey(dep)) {
                    diagnostics.add(Diagnostic.error(0,
                            "包 " + node.key() + " 的子引用悬空: " + dep + " 没有对应的 package 声明"));
                }
            }
        }

        return finish(null, roots, packages);
    }

    private ParseResult finish(Void unused, List<String> roots, TreeMap<String, PackageNode> packages) {
        roots.sort(String::compareTo);
        boolean hasError = diagnostics.stream().anyMatch(d -> d.severity == Diagnostic.Severity.ERROR);
        LockGraph graph = hasError ? null : new LockGraph(roots, packages);
        return new ParseResult(graph, diagnostics);
    }

    private void parsePackage(TreeMap<String, PackageNode> packages) {
        Token headTok = peek();
        String head = expectString("package 后需要 \"name@version\" 头");
        if (head == null) {
            skipToNextDecl();
            return;
        }
        String key = checkRef(head, headTok.line);
        String name = key == null ? "?" : Keys.nameOf(key);
        String version = key == null ? "?" : Keys.versionOf(key);

        expectSymbol('{', "package 头之后需要 '{'");
        String source = null;
        String integrity = null;
        PlatformExpr platform = null;
        List<String> deps = new ArrayList<>();
        boolean dup = false;

        while (!atEnd() && !peek().isSymbol('}')) {
            Token f = peek();
            if (f.isIdent("source")) {
                next();
                String v = expectString("source 需要字符串值");
                if (source != null) error(f, "重复的 source 字段");
                source = v;
            } else if (f.isIdent("integrity")) {
                next();
                String v = expectString("integrity 需要字符串值");
                if (integrity != null) error(f, "重复的 integrity 字段");
                integrity = v;
            } else if (f.isIdent("platform")) {
                next();
                String v = expectString("platform 需要字符串表达式");
                if (platform != null) error(f, "重复的 platform 字段");
                if (v != null) {
                    try {
                        platform = PlatformExpr.parse(v);
                    } catch (PlatformExpr.PlatformSyntaxException ex) {
                        error(f, "平台条件无法解析: " + ex.getMessage());
                    }
                }
            } else if (f.isIdent("dep")) {
                next();
                String v = expectString("dep 需要 \"name@version\" 引用");
                if (v != null) {
                    String depKey = checkRef(v, f.line);
                    if (depKey != null) {
                        if (deps.contains(depKey)) {
                            error(f, "重复的子引用: " + depKey);
                        } else {
                            deps.add(depKey);
                        }
                    }
                }
            } else {
                error(f, "无法识别的字段: " + f.text);
                next();
            }
        }
        expectSymbol('}', "package 块缺少 '}'");

        if (key == null) return;
        if (source == null || source.isEmpty()) {
            error(headTok, "包 " + key + " 缺少 source 字段");
        }
        if (integrity == null || integrity.isEmpty()) {
            error(headTok, "包 " + key + " 缺少 integrity 字段");
        }
        if (packages.containsKey(key)) {
            error(headTok, "重复的 package 声明: " + key);
            dup = true;
        }
        if (!dup && source != null && integrity != null) {
            deps.sort(String::compareTo);
            packages.put(key, new PackageNode(name, version, source, integrity, platform, deps));
        }
    }

    private String checkRef(String ref, int line) {
        int at = ref.lastIndexOf('@');
        if (at <= 0 || at == ref.length() - 1) {
            diagnostics.add(Diagnostic.error(line, "引用必须是 \"name@version\" 形式: " + ref));
            return null;
        }
        String name = ref.substring(0, at);
        String version = ref.substring(at + 1);
        if (!Keys.isValidName(name) || !Keys.isValidVersion(version)) {
            diagnostics.add(Diagnostic.error(line, "引用包含非法字符: " + ref));
            return null;
        }
        return Keys.of(name, version);
    }

    private void skipToNextDecl() {
        while (!atEnd() && !peek().isIdent("root") && !peek().isIdent("package")) next();
    }

    private String expectString(String message) {
        Token toKen = peek();
        if (toKen.kind == Kind.STRING) {
            next();
            return toKen.text;
        }
        error(toKen, message);
        return null;
    }

    private void expectSymbol(char c, String message) {
        if (peek().isSymbol(c)) {
            next();
        } else {
            error(peek(), message);
        }
    }

    private void error(Token t, String message) {
        diagnostics.add(Diagnostic.error(t.line, message));
    }

    private Token peek() {
        return tokens.get(Math.min(pos, tokens.size() - 1));
    }

    private Token next() {
        return tokens.get(pos < tokens.size() - 1 ? pos++ : pos);
    }

    private boolean atEnd() {
        return peek().kind == Kind.EOF;
    }

    private enum Kind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    private static final class Token {
        final Kind kind;
        final String text;
        final int line;
        final char symbol;

        Token(Kind kind, String text, int line, char symbol) {
            this.kind = kind;
            this.text = text;
            this.line = line;
            this.symbol = symbol;
        }

        boolean isIdent(String s) {
            return kind == Kind.IDENT && text.equals(s);
        }

        boolean isSymbol(char c) {
            return kind == Kind.SYMBOL && symbol == c;
        }
    }

    private static List<Token> lex(String text) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                while (i < n && text.charAt(i) != '\n') i++;
            } else if (c == '"') {
                int startLine = line;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char ch = text.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        char esc = text.charAt(i + 1);
                        sb.append(esc == 'n' ? '\n' : esc == 't' ? '\t' : esc);
                        i += 2;
                    } else if (ch == '"') {
                        i++;
                        closed = true;
                        break;
                    } else {
                        if (ch == '\n') line++;
                        sb.append(ch);
                        i++;
                    }
                }
                if (!closed) {
                    out.add(new Token(Kind.STRING, sb.toString(), startLine, '\0'));
                    out.add(new Token(Kind.EOF, "", startLine, '\0'));
                    return out;
                }
                out.add(new Token(Kind.STRING, sb.toString(), startLine, '\0'));
            } else if (c == '{' || c == '}') {
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), line, c));
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n) {
                    char ch = text.charAt(i);
                    if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') i++;
                    else break;
                }
                out.add(new Token(Kind.IDENT, text.substring(start, i), line, '\0'));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && Character.isDigit(text.charAt(i))) i++;
                out.add(new Token(Kind.NUMBER, text.substring(start, i), line, '\0'));
            } else {
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), line, c));
                i++;
            }
        }
        out.add(new Token(Kind.EOF, "", line, '\0'));
        return out;
    }
}
