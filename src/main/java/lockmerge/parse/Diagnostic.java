package lockmerge.parse;

public final class Diagnostic {
    public enum Severity { ERROR, WARNING }

    public final Severity severity;
    public final int line;
    public final String message;

    public Diagnostic(Severity severity, int line, String message) {
        this.severity = severity;
        this.line = line;
        this.message = message;
    }

    public static Diagnostic error(int line, String message) {
        return new Diagnostic(Severity.ERROR, line, message);
    }

    public static Diagnostic warning(int line, String message) {
        return new Diagnostic(Severity.WARNING, line, message);
    }

    @Override
    public String toString() {
        return severity + " 第" + line + "行: " + message;
    }
}
