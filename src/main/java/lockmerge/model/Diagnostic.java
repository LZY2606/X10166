package lockmerge.model;

/** A parse diagnostic attached to one input document. */
public record Diagnostic(Severity severity, int line, String code, String message) {

    public enum Severity { ERROR, WARNING }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return severity + " line " + line + " " + code + ": " + message;
    }
}
