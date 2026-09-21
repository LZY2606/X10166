package dev.lockmerge.lock;

import dev.lockmerge.model.LockDocument;
import dev.lockmerge.model.Node;
import dev.lockmerge.model.Ref;

import java.util.Comparator;
import java.util.List;

/**
 * Canonical printer. Output ordering is fully deterministic so that identical
 * semantic graphs always produce byte-identical text:
 *
 * <ul>
 *   <li>roots sorted by name, version, normalized condition;</li>
 *   <li>packages sorted by name then version;</li>
 *   <li>children sorted by name, version, normalized condition.</li>
 * </ul>
 */
public final class LockPrinter {

    private static final Comparator<Ref> REF_ORDER =
            Comparator.comparing(Ref::name)
                    .thenComparing(Ref::version)
                    .thenComparing(Ref::condition, Comparator.nullsFirst(Comparator.naturalOrder()));

    private LockPrinter() {
    }

    public static String print(LockDocument document) {
        StringBuilder out = new StringBuilder();
        out.append("lockfile v1\n");

        List<Ref> roots = document.roots().stream().sorted(REF_ORDER).toList();
        for (Ref root : roots) {
            out.append("root ").append(formatRef(root)).append('\n');
        }

        List<Node> nodes = document.nodes().stream()
                .sorted(Comparator.comparing(Node::name).thenComparing(Node::version))
                .toList();
        for (Node node : nodes) {
            out.append('\n');
            out.append("package ").append(node.id()).append('\n');
            out.append("  source ").append(node.source()).append('\n');
            out.append("  integrity ").append(node.integrity()).append('\n');
            if (node.platform() != null) {
                out.append("  platform ").append(node.platform()).append('\n');
            }
            List<Ref> children = node.children().stream().sorted(REF_ORDER).toList();
            for (Ref child : children) {
                out.append("  child ").append(formatRef(child)).append('\n');
            }
        }
        return out.toString();
    }

    private static String formatRef(Ref ref) {
        String base = ref.name() + "@" + ref.version();
        if (ref.condition() == null) {
            return base;
        }
        return base + " [" + ref.condition() + "]";
    }
}
