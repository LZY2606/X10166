package lockmerge.model;

import java.util.List;

/** Outcome of parsing one raw lockfile. */
public record ParseResult(String label, String raw, String fingerprint,
                          LockDocument document, List<Diagnostic> diagnostics) {

    public boolean hasErrors() {
        for (Diagnostic d : diagnostics) {
            if (d.isError()) {
                return true;
            }
        }
        return false;
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(d -> !d.isError()).toList();
    }
}
