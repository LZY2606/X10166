package lockmerge.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** 解析后的 lockfile 图：根依赖键集合 + 包节点表。 */
public final class LockGraph {
    public final List<String> roots; // 升序
    public final SortedMap<String, PackageNode> packages;

    public LockGraph(List<String> roots, SortedMap<String, PackageNode> packages) {
        this.roots = List.copyOf(roots);
        this.packages = new TreeMap<>(packages);
    }

    public static LockGraph empty() {
        return new LockGraph(List.of(), new TreeMap<>());
    }

    /** 从根可达的节点键集合；悬空引用会被跳过（由校验单独报告）。 */
    public Set<String> reachable() {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            String key = stack.pop();
            if (!seen.add(key)) continue;
            PackageNode node = packages.get(key);
            if (node != null) stack.addAll(node.deps);
        }
        return seen;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LockGraph g)) return false;
        return roots.equals(g.roots) && packages.equals(g.packages);
    }

    @Override
    public int hashCode() {
        return roots.hashCode() * 31 + packages.hashCode();
    }
}
