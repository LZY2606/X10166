package dev.lockmerge.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A package node in the lock graph: identity is (name, version). Two nodes with
 * the same identity are compared semantically (source, integrity, node-level
 * platform condition and ordered, normalized child references).
 */
public record Node(String name,
                   String version,
                   String source,
                   String integrity,
                   String platform,
                   List<Ref> children) {

    public Node {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(integrity, "integrity");
        children = List.copyOf(children == null ? List.of() : children);
    }

    public String id() {
        return name + "@" + version;
    }

    public Node withChildren(List<Ref> newChildren) {
        return new Node(name, version, source, integrity, platform, new ArrayList<>(newChildren));
    }

    public Node withIntegrity(String newIntegrity) {
        return new Node(name, version, source, newIntegrity, platform, children);
    }
}
