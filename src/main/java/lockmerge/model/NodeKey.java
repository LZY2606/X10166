package lockmerge.model;

import java.util.Comparator;
import java.util.Objects;

/**
 * Exact identity of a package node: name + version.
 * Same name with different versions are distinct nodes and may coexist.
 */
public record NodeKey(String name, String version) implements Comparable<NodeKey> {

    private static final Comparator<NodeKey> ORDER =
            Comparator.comparing(NodeKey::name).thenComparing(NodeKey::version);

    public NodeKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }

    public static NodeKey parse(String reference) {
        int idx = reference.indexOf('@');
        if (idx <= 0 || idx == reference.length() - 1) {
            throw new IllegalArgumentException(
                    "invalid node reference '" + reference + "', expected name@version");
        }
        return new NodeKey(reference.substring(0, idx), reference.substring(idx + 1));
    }

    public String reference() {
        return name + "@" + version;
    }

    @Override
    public int compareTo(NodeKey other) {
        return ORDER.compare(this, other);
    }
}
