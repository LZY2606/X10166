package lockmerge.merge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lockmerge.model.Keys;
import lockmerge.model.LockGraph;
import lockmerge.model.PackageNode;
import lockmerge.model.PlatformExpr;

/**
 * 三方语义合并：
 *  - 根依赖做集合三方合并；
 *  - 节点按 name@version 对齐，字段级三方合并（平台条件按规范化表达式比较）；
 *  - 删除 vs 修改仅当节点在合并后根可达时才构成冲突，否则交由可达清理；
 *  - 合并后做父引用重校验（悬空自动回补或报错）与根可达清理，并解释清理内容；
 *  - 同一来源坐标 + 版本的摘要必须一致，否则记入完整性违例。
 */
public final class Merger {

    public static MergeResult merge(LockGraph base, LockGraph left, LockGraph right,
                                    Map<String, String> decisions) {
        List<Conflict> conflicts = new ArrayList<>();

        // 1. 根依赖集合三方合并
        Set<String> roots = mergeRootSet(base, left, right);

        // 2. 合并后根在“左右并集图”中的可达集，用于判定删除 vs 修改是否值得成为冲突
        Set<String> unionReachable = unionReachable(roots, left, right);

        // 3. 节点级三方合并
        TreeMap<String, PackageNode> merged = new TreeMap<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(base.packages.keySet());
        allKeys.addAll(left.packages.keySet());
        allKeys.addAll(right.packages.keySet());

        for (String key : allKeys) {
            PackageNode b = base.packages.get(key);
            PackageNode l = left.packages.get(key);
            PackageNode r = right.packages.get(key);

            if (Objects.equals(l, r)) {
                if (l != null) merged.put(key, l);
                continue;
            }
            if (Objects.equals(b, l)) {
                if (r != null) merged.put(key, r);
                continue;
            }
            if (Objects.equals(b, r)) {
                if (l != null) merged.put(key, l);
                continue;
            }

            if (l != null && r != null) {
                merged.put(key, mergeNodeFields(key, b, l, r, decisions, conflicts));
            } else {
                // 删除 vs 修改：不可达则保留现状并交给可达清理，不产生冲突
                PackageNode present = l != null ? l : r;
                if (unionReachable.contains(key)) {
                    String id = "node:" + key + ":existence";
                    String choice = decisions.get(id);
                    Conflict c = new Conflict(id, key, "existence",
                            l == null ? "(删除)" : describe(l),
                            r == null ? "(删除)" : describe(r),
                            "一侧删除了节点 " + key + "，另一侧修改了它，且该节点仍可从根到达",
                            choice);
                    conflicts.add(c);
                    if ("left".equals(choice)) {
                        if (l != null) merged.put(key, l);
                    } else if ("right".equals(choice)) {
                        if (r != null) merged.put(key, r);
                    } else {
                        merged.put(key, present); // 临时保留，等待裁决
                    }
                } else {
                    merged.put(key, present);
                }
            }
        }

        // 4. 父引用重校验：悬空引用优先从各侧回补，找不到则报错
        List<String> validationErrors = new ArrayList<>();
        repairDangling(merged, roots, base, left, right, validationErrors);

        // 5. 根可达清理
        List<PrunedNode> pruned = new ArrayList<>();
        Set<String> baseReachable = base.reachable();
        Set<String> reachable = reachableFrom(roots, merged);
        List<String> unreachable = new ArrayList<>();
        for (String key : merged.keySet()) {
            if (!reachable.contains(key)) unreachable.add(key);
        }
        for (String key : unreachable) {
            merged.remove(key);
            String reason = baseReachable.contains(key)
                    ? "在 base 中可达，但合并后的根依赖已无法到达（根被删除或依赖链改向），已清理"
                    : "不被任何合并后的根依赖引用，已清理";
            pruned.add(new PrunedNode(key, reason));
        }

        // 6. 完整性检查：同一来源坐标 + 版本的摘要必须一致
        List<String> integrityViolations = integrityCheck(merged);

        LockGraph graph = new LockGraph(new ArrayList<>(roots), merged);
        return new MergeResult(graph, conflicts, pruned, integrityViolations, validationErrors);
    }

    private static Set<String> mergeRootSet(LockGraph base, LockGraph left, LockGraph right) {
        Set<String> roots = new TreeSet<>();
        Set<String> all = new TreeSet<>();
        all.addAll(base.roots);
        all.addAll(left.roots);
        all.addAll(right.roots);
        for (String key : all) {
            boolean inB = base.roots.contains(key);
            boolean inL = left.roots.contains(key);
            boolean inR = right.roots.contains(key);
            boolean keep;
            if (inL == inR) keep = inL;
            else if (inB == inL) keep = inR;
            else keep = inL;
            if (keep) roots.add(key);
        }
        return roots;
    }

    private static PackageNode mergeNodeFields(String key, PackageNode b, PackageNode l, PackageNode r,
                                               Map<String, String> decisions, List<Conflict> conflicts) {
        String source = pick3(key, "source", b.source, l.source, r.source, decisions, conflicts);
        String integrity = pick3(key, "integrity", b.integrity, l.integrity, r.integrity, decisions, conflicts);

        String bPlat = b.platform == null ? null : b.platform.canonical();
        String lPlat = l.platform == null ? null : l.platform.canonical();
        String rPlat = r.platform == null ? null : r.platform.canonical();
        String plat = pick3(key, "platform", bPlat, lPlat, rPlat, decisions, conflicts);
        PlatformExpr platform = plat == null ? null : PlatformExpr.parse(plat);

        List<String> deps = mergeDepSet(b.deps, l.deps, r.deps);
        return new PackageNode(Keys.nameOf(key), Keys.versionOf(key), source, integrity, platform, deps);
    }

    /** 字段级三方合并；双侧不同修改时记录冲突并应用已有裁决（未裁决时临时取左侧）。 */
    private static String pick3(String key, String field, String b, String l, String r,
                                Map<String, String> decisions, List<Conflict> conflicts) {
        if (Objects.equals(l, r)) return l;
        if (Objects.equals(b, l)) return r;
        if (Objects.equals(b, r)) return l;
        String id = "node:" + key + ":" + field;
        String choice = decisions.get(id);
        conflicts.add(new Conflict(id, key, field,
                l == null ? "(无)" : l,
                r == null ? "(无)" : r,
                "节点 " + key + " 的 " + field + " 字段被双侧不同修改",
                choice));
        if ("right".equals(choice)) return r;
        return l; // 未裁决或选左：临时取左
    }

    private static List<String> mergeDepSet(List<String> b, List<String> l, List<String> r) {
        Set<String> result = new TreeSet<>();
        Set<String> all = new TreeSet<>();
        all.addAll(b);
        all.addAll(l);
        all.addAll(r);
        for (String dep : all) {
            boolean inB = b.contains(dep);
            boolean inL = l.contains(dep);
            boolean inR = r.contains(dep);
            boolean keep;
            if (inL == inR) keep = inL;
            else if (inB == inL) keep = inR;
            else keep = inL;
            if (keep) result.add(dep);
        }
        return new ArrayList<>(result);
    }

    private static Set<String> unionReachable(Set<String> roots, LockGraph left, LockGraph right) {
        TreeMap<String, PackageNode> union = new TreeMap<>(left.packages);
        union.putAll(right.packages);
        return reachableFrom(roots, union);
    }

    private static Set<String> reachableFrom(Set<String> roots, Map<String, PackageNode> nodes) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            String key = stack.pop();
            if (!seen.add(key)) continue;
            PackageNode node = nodes.get(key);
            if (node != null) stack.addAll(node.deps);
        }
        return seen;
    }

    /** 重校验父引用：缺失目标从 left/right/base 回补；无处可补则记录校验错误。 */
    private static void repairDangling(TreeMap<String, PackageNode> merged, Set<String> roots,
                                       LockGraph base, LockGraph left, LockGraph right,
                                       List<String> validationErrors) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PackageNode node : new ArrayList<>(merged.values())) {
                for (String dep : node.deps) {
                    if (merged.containsKey(dep)) continue;
                    PackageNode donor = left.packages.get(dep);
                    if (donor == null) donor = right.packages.get(dep);
                    if (donor == null) donor = base.packages.get(dep);
                    if (donor != null) {
                        merged.put(dep, donor);
                        changed = true;
                    } else {
                        validationErrors.add("节点 " + node.key() + " 引用了不存在的子节点 " + dep
                                + "，三份输入中均无法回补");
                    }
                }
            }
        }
        for (String root : roots) {
            if (!merged.containsKey(root)) {
                PackageNode donor = left.packages.get(root);
                if (donor == null) donor = right.packages.get(root);
                if (donor == null) donor = base.packages.get(root);
                if (donor != null) {
                    merged.put(root, donor);
                } else {
                    validationErrors.add("根依赖 " + root + " 在任何输入中都没有对应节点");
                }
            }
        }
    }

    private static List<String> integrityCheck(TreeMap<String, PackageNode> merged) {
        Map<String, Map<String, String>> bySourceVersion = new TreeMap<>();
        for (PackageNode node : merged.values()) {
            String sv = node.source + "@" + node.version;
            bySourceVersion.computeIfAbsent(sv, k -> new TreeMap<>())
                    .put(node.integrity, node.key());
        }
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : bySourceVersion.entrySet()) {
            if (e.getValue().size() > 1) {
                violations.add("来源坐标 " + e.getKey() + " 存在互相冲突的摘要: "
                        + String.join(" vs ", e.getValue().keySet()));
            }
        }
        return violations;
    }

    private static String describe(PackageNode node) {
        return "source=" + node.source + " integrity=" + node.integrity
                + (node.platform != null ? " platform=\"" + node.platform.canonical() + "\"" : "");
    }
}
