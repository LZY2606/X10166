package lockmerge.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A package node in the lock graph.
 *
 * @param key        name + version
 * @param source     source coordinate, e.g. registry:acme/lib or git:https://x#a1b2
 * @param integrity  integrity digest string (algorithm:hex), or {@code null}
 * @param platforms  canonical platform condition making the whole node conditional, or {@code null}
 * @param requires   precise edges to child nodes
 */
public final class PackageNode {

    private final NodeKey key;
    private final String source;
    private final String integrity;
    private final String platforms;
    private final List<EdgeRef> requires;

    public PackageNode(NodeKey key, String source, String integrity,
                       String platforms, List<EdgeRef> requires) {
        this.key = Objects.requireNonNull(key, "key");
        this.source = Objects.requireNonNull(source, "source");
        this.integrity = integrity;
        this.platforms = platforms;
        this.requires = List.copyOf(requires);
    }

    public NodeKey key() {
        return key;
    }

    public String source() {
        return source;
    }

    public String integrity() {
        return integrity;
    }

    public String platforms() {
        return platforms;
    }

    public List<EdgeRef> requires() {
        return requires;
    }

    public PackageNode withRequires(List<EdgeRef> newRequires) {
        return new PackageNode(key, source, integrity, platforms, newRequires);
    }

    public List<NodeKey> childKeys() {
        List<NodeKey> result = new ArrayList<>(requires.size());
        for (EdgeRef edge : requires) {
            result.add(edge.target());
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PackageNode that)) {
            return false;
        }
        return key.equals(that.key)
                && source.equals(that.source)
                && Objects.equals(integrity, that.integrity)
                && Objects.equals(platforms, that.platforms)
                && requires.equals(that.requires);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, source, integrity, platforms, requires);
    }

    @Override
    public String toString() {
        return "PackageNode[" + key.reference() + "]";
    }
}
