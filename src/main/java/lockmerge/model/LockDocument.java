package lockmerge.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A fully parsed lock document: ordered roots plus node table. */
public final class LockDocument {

    private final int formatVersion;
    private final List<RootDep> roots;
    private final Map<NodeKey, PackageNode> nodes;

    public LockDocument(int formatVersion, List<RootDep> roots, Map<NodeKey, PackageNode> nodes) {
        this.formatVersion = formatVersion;
        this.roots = List.copyOf(roots);
        Map<NodeKey, PackageNode> copy = new LinkedHashMap<>();
        for (Map.Entry<NodeKey, PackageNode> e : nodes.entrySet()) {
            copy.put(e.getKey(), e.getValue());
        }
        this.nodes = Collections.unmodifiableMap(copy);
    }

    public int formatVersion() {
        return formatVersion;
    }

    public List<RootDep> roots() {
        return roots;
    }

    public Map<NodeKey, PackageNode> nodes() {
        return nodes;
    }

    public PackageNode node(NodeKey key) {
        return nodes.get(key);
    }

    public RootDep root(String name) {
        for (RootDep root : roots) {
            if (root.name().equals(name)) {
                return root;
            }
        }
        return null;
    }

    public List<NodeKey> danglingReferences() {
        List<NodeKey> result = new ArrayList<>();
        for (RootDep root : roots) {
            if (!nodes.containsKey(root.pin()) && !result.contains(root.pin())) {
                result.add(root.pin());
            }
        }
        for (PackageNode node : nodes.values()) {
            for (EdgeRef edge : node.requires()) {
                NodeKey target = edge.target();
                if (!nodes.containsKey(target) && !result.contains(target)) {
                    result.add(target);
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LockDocument that)) {
            return false;
        }
        return formatVersion == that.formatVersion
                && roots.equals(that.roots)
                && nodes.equals(that.nodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formatVersion, roots, nodes);
    }
}
