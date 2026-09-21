package dev.lockmerge.model;

/** A parse or validation diagnostic attached to one of the three inputs. */
public record Diagnostic(Severity severity, int line, String code, String message) {

    public enum Severity { ERROR, WARNING }

    public static Diagnostic error(int line, String code, String message) {
        return new Diagnostic(Severity.ERROR, line, code, message);
    }

    public static Diagnostic warning(int line, String code, String message) {
        return new Diagnostic(Severity.WARNING, line, code, message);
    }
}
