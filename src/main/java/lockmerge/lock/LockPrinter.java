package lockmerge.lock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lockmerge.model.EdgeRef;
import lockmerge.model.LockDocument;
import lockmerge.model.NodeKey;
import lockmerge.model.PackageNode;
import lockmerge.model.RootDep;

/**
 * Canonical, stable-order printer for lock documents.
 *
 * <p>Roots keep their declared order; packages are sorted by name then version;
 * edges within a package are sorted by target key then canonical condition.
 * Printing then parsing yields an equal {@link LockDocument}.
 */
public final class LockPrinter {

    private LockPrinter() {
    }

    public static String print(LockDocument document) {
        StringBuilder sb = new StringBuilder();
        sb.append("lockfile v").append(document.formatVersion()).append('\n');
        for (RootDep root : document.roots()) {
            sb.append("root ").append(root.reference()).append('\n');
        }

        List<PackageNode> sorted = new ArrayList<>(document.nodes().values());
        sorted.sort(Comparator.comparing(PackageNode::key));

        for (PackageNode node : sorted) {
            sb.append("package ").append(node.key().reference()).append('\n');
            sb.append("    source ").append(node.source()).append('\n');
            if (node.integrity() != null) {
                sb.append("    integrity ").append(node.integrity()).append('\n');
            }
            if (node.platforms() != null) {
                sb.append("    platforms ").append(node.platforms()).append('\n');
            }
            List<EdgeRef> edges = new ArrayList<>(node.requires());
            edges.sort(Comparator.comparing(EdgeRef::target)
                    .thenComparing(edge -> edge.platforms() == null
                            ? "" : edge.platforms(), Comparator.nullsFirst(Comparator.naturalOrder())));
            if (!edges.isEmpty()) {
                sb.append("    requires:\n");
                for (EdgeRef edge : edges) {
                    sb.append("        ").append(edge.target().reference());
                    if (edge.platforms() != null) {
                        sb.append(" when ").append(edge.platforms());
                    }
                    sb.append('\n');
                }
                sb.append("    end\n");
            }
        }
        return sb.toString();
    }

    static String keyOf(NodeKey key) {
        return key.reference();
    }
}
