package mergeroom.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A locked package node. Identity key is name@version; same name with
 * different versions coexists as separate nodes.
 */
public final class PackageNode {
    private final String name;
    private final String version;
    private String source;
    private String integrity;
    private String condition;
    private final Map<String, Edge> edges; // keyed by child package name (one exact reference per child)

    public PackageNode(String name, String version, String source, String integrity,
                       String condition, Map<String, Edge> edges) {
        this.name = Objects.requireNonNull(name);
        this.version = Objects.requireNonNull(version);
        this.source = source == null ? "" : source;
        this.integrity = integrity == null ? "" : integrity;
        this.condition = condition == null ? "" : condition;
        this.edges = new LinkedHashMap<>(edges == null ? Map.of() : edges);
    }

    public String name() { return name; }
    public String version() { return version; }
    public String source() { return source; }
    public String integrity() { return integrity; }
    public String condition() { return condition; }
    public Map<String, Edge> edges() { return edges; }

    public String key() {
        return name + "@" + version;
    }

    public Optional<String> platformCondition() {
        return condition.isEmpty() ? Optional.empty() : Optional.of(condition);
    }

    public void setSource(String source) {
        this.source = source == null ? "" : source;
    }

    public void setIntegrity(String integrity) {
        this.integrity = integrity == null ? "" : integrity;
    }

    public void setCondition(String condition) {
        this.condition = condition == null ? "" : condition;
    }

    public PackageNode copy() {
        return new PackageNode(name, version, source, integrity, condition, new LinkedHashMap<>(edges));
    }

    public boolean contentEquals(PackageNode other) {
        return name.equals(other.name)
                && version.equals(other.version)
                && source.equals(other.source)
                && integrity.equals(other.integrity)
                && condition.equals(other.condition)
                && edges.equals(other.edges);
    }
}
