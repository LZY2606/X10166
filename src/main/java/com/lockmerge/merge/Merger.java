package com.lockmerge.merge;

import com.lockmerge.lockfile.LockfilePrinter;
import com.lockmerge.lockfile.Model.Lockfile;
import com.lockmerge.lockfile.Model.NameVersion;
import com.lockmerge.lockfile.Model.PackageNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Semantic three-way merge of lockfiles.
 *
 * <ul>
 *   <li>Node identity is {@code name@version}; same name with different versions coexists.</li>
 *   <li>Platform conditions are compared by normalized form.</li>
 *   <li>Nodes unreachable from merged roots are pruned (with explanations), never reported
 *       as conflicts.</li>
 *   <li>Integrity: same source coordinate + version must not carry conflicting digests;
 *       dangling references are reported.</li>
 * </ul>
 */
public final class Merger {

    private Merger() {}

    /** Serializable view of a package node (null side means deleted/absent). */
    public record NodeView(String key, String name, String version, String source,
                           String integrity, String platform, List<String> depends) {
        static NodeView of(PackageNode n) {
            if (n == null) return null;
            List<String> deps = new ArrayList<>();
            for (NameVersion d : n.depends) deps.add(d.toString());
            return new NodeView(n.key().toString(), n.name, n.version, n.source, n.integrity,
                    n.platform == null ? null : n.platform.canonical(), deps);
        }
    }

    public record Conflict(String id, String key, String kind,
                           NodeView base, NodeView left, NodeView right) {}

    public record PruneNote(String key, String reason) {}

    public record GraphNode(NodeView node, boolean reachable, boolean isRoot) {}

    public static final class MergeResult {
        public final List<Conflict> pendingConflicts = new ArrayList<>();
        public final List<String> autoResolvedNotes = new ArrayList<>();
        public final List<PruneNote> pruned = new ArrayList<>();
        public final List<String> integrityIssues = new ArrayList<>();
        public final List<GraphNode> graph = new ArrayList<>();
        public final List<String> mergedRoots = new ArrayList<>();
        /** Printed merged lockfile; null while conflicts are pending. */
        public String output;
    }

    /**
     * @param resolutions conflictId -> "left" or "right" (already fingerprint-validated)
     */
    public static MergeResult merge(Lockfile base, Lockfile left, Lockfile right,
                                    Map<String, String> resolutions) {
        MergeResult result = new MergeResult();

        // 1. roots: element-wise three-way
        Set<NameVersion> mergedRoots = new TreeSet<>();
        Set<NameVersion> allRoots = new TreeSet<>();
        allRoots.addAll(base.roots);
        allRoots.addAll(left.roots);
        allRoots.addAll(right.roots);
        for (NameVersion r : allRoots) {
            boolean b = base.roots.contains(r);
            boolean l = left.roots.contains(r);
            boolean rr = right.roots.contains(r);
            if (l == rr) { if (l) mergedRoots.add(r); }
            else if (l == b) { if (rr) mergedRoots.add(r); }
            else { if (l) mergedRoots.add(r); }
        }
        for (NameVersion r : mergedRoots) result.mergedRoots.add(r.toString());

        // 2. per-node three-way
        Set<NameVersion> allKeys = new TreeSet<>();
        allKeys.addAll(base.packages.keySet());
        allKeys.addAll(left.packages.keySet());
        allKeys.addAll(right.packages.keySet());

        Map<NameVersion, PackageNode> merged = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (NameVersion key : allKeys) {
            PackageNode b = base.packages.get(key);
            PackageNode l = left.packages.get(key);
            PackageNode r = right.packages.get(key);
            if (semEq(l, r)) {
                if (l != null) merged.put(key, l.copy());
            } else if (semEq(l, b)) {
                if (r != null) merged.put(key, r.copy());
            } else if (semEq(r, b)) {
                if (l != null) merged.put(key, l.copy());
            } else {
                String id = "node:" + key;
                String kind = kindOf(b, l, r);
                conflicts.add(new Conflict(id, key.toString(), kind,
                        NodeView.of(b), NodeView.of(l), NodeView.of(r)));
                String choice = resolutions.get(id);
                PackageNode tentative = "right".equals(choice) ? r : l;
                if (tentative != null) merged.put(key, tentative.copy());
            }
        }

        // 3. reachability from merged roots
        Set<NameVersion> reachable = reachableFrom(mergedRoots, merged);

        // 4. split conflicts: unreachable ones are auto-resolved by pruning, never pending
        List<Conflict> pending = new ArrayList<>();
        for (Conflict c : conflicts) {
            NameVersion key = NameVersion.parse(c.key());
            if (reachable.contains(key)) {
                pending.add(c);
            } else {
                result.autoResolvedNotes.add(
                        "冲突 " + c.key() + " 涉及的节点在合并结果中不可达，已随不可达清理自动忽略");
            }
        }
        result.pendingConflicts.addAll(pending);

        // 5. prune unreachable nodes with explanations
        Map<NameVersion, Set<NameVersion>> baseReachByRoot = baseReachability(base);
        List<NameVersion> sortedKeys = new ArrayList<>(merged.keySet());
        sortedKeys.sort(NameVersion::compareTo);
        for (NameVersion key : sortedKeys) {
            if (!reachable.contains(key)) {
                result.pruned.add(new PruneNote(key.toString(), pruneReason(key, base, mergedRoots, baseReachByRoot)));
            }
        }

        // 6. final graph = reachable nodes only
        Lockfile out = new Lockfile();
        out.roots.addAll(mergedRoots);
        for (NameVersion key : reachable) {
            PackageNode n = merged.get(key);
            if (n != null) out.packages.put(key, n);
        }

        // 7. integrity checks on the final reachable graph
        checkIntegrity(out, result.integrityIssues);

        // 8. graph view (pre-prune, with reachability flags)
        for (NameVersion key : sortedKeys) {
            PackageNode n = merged.get(key);
            result.graph.add(new GraphNode(NodeView.of(n), reachable.contains(key), mergedRoots.contains(key)));
        }

        // 9. output only when no pending conflicts
        if (pending.isEmpty()) {
            result.output = LockfilePrinter.print(out);
        }
        return result;
    }

    private static boolean semEq(PackageNode x, PackageNode y) {
        if (x == null || y == null) return x == y;
        return x.semanticEquals(y);
    }

    private static String kindOf(PackageNode b, PackageNode l, PackageNode r) {
        if (b == null) return "added-both-different";
        if (l == null) return "deleted-left-modified-right";
        if (r == null) return "modified-left-deleted-right";
        return "modified-both";
    }

    private static Set<NameVersion> reachableFrom(Set<NameVersion> roots, Map<NameVersion, PackageNode> nodes) {
        Set<NameVersion> seen = new LinkedHashSet<>();
        Deque<NameVersion> stack = new ArrayDeque<>();
        for (NameVersion r : roots) {
            if (nodes.containsKey(r)) stack.push(r);
        }
        while (!stack.isEmpty()) {
            NameVersion cur = stack.pop();
            if (!seen.add(cur)) continue;
            PackageNode node = nodes.get(cur);
            if (node == null) continue;
            for (NameVersion dep : node.depends) {
                if (nodes.containsKey(dep) && !seen.contains(dep)) stack.push(dep);
            }
        }
        return seen;
    }

    private static Map<NameVersion, Set<NameVersion>> baseReachability(Lockfile base) {
        Map<NameVersion, Set<NameVersion>> byRoot = new LinkedHashMap<>();
        for (NameVersion root : base.roots) {
            Set<NameVersion> one = new TreeSet<>();
            one.add(root);
            byRoot.put(root, reachableFrom(one, base.packages));
        }
        return byRoot;
    }

    private static String pruneReason(NameVersion key, Lockfile base, Set<NameVersion> mergedRoots,
                                      Map<NameVersion, Set<NameVersion>> baseReachByRoot) {
        List<String> lostRoots = new ArrayList<>();
        for (Map.Entry<NameVersion, Set<NameVersion>> e : baseReachByRoot.entrySet()) {
            if (e.getValue().contains(key) && !mergedRoots.contains(e.getKey())) {
                lostRoots.add(e.getKey().toString());
            }
        }
        if (!lostRoots.isEmpty()) {
            return "在 base 中仅经由根 [" + String.join(", ", lostRoots) + "] 可达，"
                    + "该根已被分支删除，节点随之不可达，已清理";
        }
        if (!base.packages.containsKey(key)) {
            return "分支新增但未被任何根依赖引用，不可达，已清理";
        }
        return "父引用在合并后不再指向该节点，不可达，已清理";
    }

    private static void checkIntegrity(Lockfile out, List<String> issues) {
        // digest consistency: same source coordinate + version must share one digest
        Map<String, Map<String, List<String>>> bySourceVersion = new TreeMap<>();
        for (PackageNode n : out.packages.values()) {
            if (n.source == null || n.integrity == null) continue;
            String sv = n.source + "@" + n.version;
            bySourceVersion.computeIfAbsent(sv, k -> new TreeMap<>())
                    .computeIfAbsent(n.integrity, k -> new ArrayList<>())
                    .add(n.key().toString());
        }
        for (Map.Entry<String, Map<String, List<String>>> e : bySourceVersion.entrySet()) {
            if (e.getValue().size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("完整性冲突: 来源 ").append(e.getKey()).append(" 存在 ")
                        .append(e.getValue().size()).append(" 个不同摘要 — ");
                e.getValue().forEach((digest, keys) ->
                        sb.append(digest).append(" 用于 ").append(keys).append("; "));
                issues.add(sb.toString());
            }
        }
        // dangling references
        for (PackageNode n : out.packages.values()) {
            for (NameVersion dep : n.depends) {
                if (!out.packages.containsKey(dep)) {
                    issues.add("悬空引用: " + n.key() + " 依赖的 " + dep + " 在合并结果中不存在");
                }
            }
        }
        // roots without package definitions
        for (NameVersion root : out.roots) {
            if (!out.packages.containsKey(root)) {
                issues.add("根依赖 " + root + " 没有对应的包定义");
            }
        }
    }
}
