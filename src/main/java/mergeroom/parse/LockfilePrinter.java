package mergeroom.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import mergeroom.model.Edge;
import mergeroom.model.Lockfile;
import mergeroom.model.PackageNode;
import mergeroom.model.RootRef;

/**
 * Deterministic, stable-sorted rendering of a lockfile. Output of this printer
 * round-trips through {@link LockfileParser} to an identical graph.
 */
public final class LockfilePrinter {

    public String print(Lockfile lf) {
        StringBuilder sb = new StringBuilder();
        sb.append(LockfileParser.MAGIC).append('\n');
        List<RootRef> roots = new ArrayList<>(lf.roots());
        roots.sort(Comparator.comparing(RootRef::name));
        for (RootRef r : roots) {
            sb.append("root ").append(q(r.name())).append(' ').append(q(r.version()));
            if (!r.condition().isEmpty()) {
                sb.append(" when ").append(q(r.condition()));
            }
            sb.append('\n');
        }
        List<PackageNode> nodes = new ArrayList<>(lf.nodes().values());
        nodes.sort(Comparator.comparing(PackageNode::key));
        for (PackageNode node : nodes) {
            sb.append("package ").append(q(node.name())).append(' ').append(q(node.version()))
                    .append(" source ").append(q(node.source()))
                    .append(" integrity ").append(q(node.integrity()));
            if (!node.condition().isEmpty()) {
                sb.append(" when ").append(q(node.condition()));
            }
            sb.append('\n');
            List<Map.Entry<String, Edge>> edges = new ArrayList<>(node.edges().entrySet());
            edges.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, Edge> e : edges) {
                Edge edge = e.getValue();
                sb.append("  require ").append(q(edge.child())).append(' ').append(q(edge.spec()));
                if (!edge.condition().isEmpty()) {
                    sb.append(" when ").append(q(edge.condition()));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
