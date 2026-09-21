package mergeroom.model;

/**
 * A single parse/validation diagnostic tied to a 1-based source line.
 */
public record Diagnostic(Severity severity, int line, String message) {
    public enum Severity { ERROR, WARNING }

    public static Diagnostic error(int line, String message) {
        return new Diagnostic(Severity.ERROR, line, message);
    }

    public static Diagnostic warning(int line, String message) {
        return new Diagnostic(Severity.WARNING, line, message);
    }
}
