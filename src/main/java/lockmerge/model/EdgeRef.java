package lockmerge.model;

import java.util.Objects;

/**
 * A precise edge from a package node to a child node, optionally guarded by a
 * normalized platform condition. The {@code platforms} field holds the
 * canonical (normalized) expression, never the raw source string.
 */
public record EdgeRef(NodeKey target, String platforms) {

    public EdgeRef {
        Objects.requireNonNull(target, "target");
    }

    public static EdgeRef of(NodeKey target) {
        return new EdgeRef(target, null);
    }

    public String platformsOrAlways() {
        return platforms == null || platforms.isBlank() ? "true" : platforms;
    }
}
