package dev.lockmerge.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed representation of one lockfile: root declarations plus package nodes. */
public record LockDocument(List<Ref> roots, List<Node> nodes) {

    public LockDocument {
        roots = List.copyOf(roots == null ? List.of() : roots);
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
    }

    public Map<String, Node> nodeIndex() {
        Map<String, Node> index = new LinkedHashMap<>();
        for (Node node : nodes) {
            index.put(node.id(), node);
        }
        return index;
    }

    public LockDocument withNodes(List<Node> newNodes) {
        return new LockDocument(new ArrayList<>(roots), new ArrayList<>(newNodes));
    }
}
