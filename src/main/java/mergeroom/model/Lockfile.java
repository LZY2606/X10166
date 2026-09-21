package mergeroom.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed lockfile: root declarations plus package nodes keyed by name@version.
 */
public final class Lockfile {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<RootRef> roots = new ArrayList<>();
    private final Map<String, PackageNode> nodes = new LinkedHashMap<>();

    public List<Diagnostic> diagnostics() { return diagnostics; }
    public List<RootRef> roots() { return roots; }
    public Map<String, PackageNode> nodes() { return nodes; }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
