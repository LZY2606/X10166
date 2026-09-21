package mergeroom.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A dependency edge from a package node to a child package name.
 * The resolved target is always a package name; versions are selected through
 * the graph (multiple same-name versions may coexist, each with its own key).
 * Condition is the normalized platform expression (empty means unconditional).
 */
public record Edge(String child, String spec, String condition) {
    public Edge {
        Objects.requireNonNull(child);
        Objects.requireNonNull(spec);
        if (condition == null) {
            condition = "";
        }
    }

    public Optional<String> platformCondition() {
        return condition.isEmpty() ? Optional.empty() : Optional.of(condition);
    }
}
