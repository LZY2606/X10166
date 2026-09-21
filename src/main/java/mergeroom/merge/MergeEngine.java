package mergeroom.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import mergeroom.model.Diagnostic;
import mergeroom.model.Edge;
import mergeroom.model.Lockfile;
import mergeroom.model.PackageNode;
import mergeroom.model.RootRef;
import mergeroom.parse.LockfileParser;
import mergeroom.parse.LockfilePrinter;

/**
 * Semantic three-way merge engine for parsed LFLOCK/1 lockfiles.
 *
 * <p>The engine builds a structural merged graph from field/edge level
 * three-way decisions, reports active conflicts (only for nodes reachable in
 * the union "possibility" graph), validates references and integrity, and
 * finally prunes nodes unreachable from the merged roots.
 */
public final class MergeEngine {

    private final Lockfile base;
    private final Lockfile left;
    private final Lockfile right;
    private final LockfilePrinter printer = new LockfilePrinter();

    public MergeEngine(Lockfile base, Lockfile left, Lockfile right) {
        this.base = Objects.requireNonNull(base);
        this.left = Objects.requireNonNull(left);
        this.right = Objects.requireNonNull(right);
    }

    /** Computes a fresh outcome applying the given id -&gt; side verdicts. */
    public MergeOutcome compute(Map<String, Side> verdicts) {
        if (verdicts == null) {
            verdicts = Map.of();
        }
        Map<String, Integer> auto = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        Lockfile merged = new Lockfile();

        mergeRoots(merged, conflicts, auto);
        mergeNodes(merged, conflicts, auto);

        addIntegrityConflicts(merged, conflicts);

        applyVerdicts(merged, conflicts, verdicts);

        List<String> integrityIssues = checkIntegrity(merged);

        List<DanglingRef> dangling = findDangling(merged);

        List<Conflict> activeMarked = markActivity(merged, conflicts, dangling);

        Set<String> reachable = reachableFromRoots(merged);
        Set<String> rootNames = new LinkedHashSet<>();
        for (RootRef r : merged.roots()) {
            rootNames.add(r.name());
        }
        List<RemovedNode> removed = explainRemovals(merged, reachable, rootNames);

        boolean unresolved = activeMarked.stream().anyMatch(c -> c.active() && c.unresolved());
        boolean danglingBlocks = !dangling.isEmpty();
        boolean integrityBlocks = !integrityIssues.isEmpty();

        List<String> blocking = new ArrayList<>();
        Lockfile finalLf = null;
        String output = null;
        if (unresolved) {
            long n = activeMarked.stream().filter(c -> c.active() && c.unresolved()).count();
            blocking.add(n + " 个待决冲突尚未处理");
        }
        if (danglingBlocks) {
            blocking.add(dangling.size() + " 个悬空引用指向不存在的精确节点");
        }
        if (integrityBlocks) {
            blocking.add(integrityIssues.size() + " 个来源/摘要完整性问题");
        }
        if (blocking.isEmpty()) {
            finalLf = prune(merged, reachable);
            List<Diagnostic> errors = finalLf.errors();
            if (!errors.isEmpty()) {
                blocking.add("合并结果仍有解析级诊断");
            } else {
                output = printer.print(finalLf);
            }
        }
        return new MergeOutcome(merged, activeMarked, dangling, removed, finalLf, output,
                blocking, auto, integrityIssues);
    }

    // ---------------------------------------------------------------- roots

    private void mergeRoots(Lockfile merged, List<Conflict> conflicts, Map<String, Integer> auto) {
        Set<String> names = new LinkedHashSet<>();
        for (RootRef r : base.roots()) names.add(r.name());
        for (RootRef r : left.roots()) names.add(r.name());
        for (RootRef r : right.roots()) names.add(r.name());
        for (String name : names) {
            RootRef b = rootByName(base, name);
            RootRef l = rootByName(left, name);
            RootRef r = rootByName(right, name);
            if (Objects.equals(l, r)) {
                if (l != null) {
                    merged.roots().add(l);
                }
                continue;
            }
            boolean leftChanged = !Objects.equals(b, l);
            boolean rightChanged = !Objects.equals(b, r);
            if (!leftChanged) {
                if (r != null) merged.roots().add(r);
                continue;
            }
            if (!rightChanged) {
                if (l != null) merged.roots().add(l);
                continue;
            }
            String id = "root:" + name;
            String desc = describeRootConflict(name, b, l, r);
            conflicts.add(new Conflict(id, ConflictKind.ROOT, name, desc,
                    opt(l), opt(r), null, true, false));
            if (l != null) {
                merged.roots().add(l);
            }
        }
        bump(auto, "roots");
    }

    private static String describeRootConflict(String name, RootRef b, RootRef l, RootRef r) {
        if (l == null || r == null) {
            Side deletedSide = l == null ? Side.LEFT : Side.RIGHT;
            RootRef kept = l == null ? r : l;
            return "根依赖 '" + name + "' 在" + (deletedSide == Side.LEFT ? "左" : "右")
                    + "侧被删除，另一侧改为 " + kept.version();
        }
        return "根依赖 '" + name + "' 两侧修改不一致："
                + opt(l) + " vs " + opt(r);
    }

    private static String opt(RootRef r) {
        if (r == null) {
            return "删除";
        }
        return r.version() + (r.condition().isEmpty() ? "" : " when " + r.condition());
    }

    private RootRef rootByName(Lockfile lf, String name) {
        for (RootRef r : lf.roots()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    private void bump(Map<String, Integer> auto, String key) {
        auto.merge(key, 1, Integer::sum);
    }

    // ---------------------------------------------------------------- nodes

    private void mergeNodes(Lockfile merged, List<Conflict> conflicts, Map<String, Integer> auto) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(base.nodes().keySet());
        keys.addAll(left.nodes().keySet());
        keys.addAll(right.nodes().keySet());
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(String::compareTo);
        for (String key : sorted) {
            PackageNode b = base.nodes().get(key);
            PackageNode l = left.nodes().get(key);
            PackageNode r = right.nodes().get(key);
            if (l != null && r != null && l.contentEquals(r)) {
                merged.nodes().put(key, l.copy());
                continue;
            }
            boolean inBase = b != null;
            if (!inBase) {
                if (l == null) {
                    // added only on the right
                    merged.nodes().put(key, r.copy());
                    bump(auto, "nodesAdded");
                    continue;
                }
                if (r == null) {
                    // added only on the left
                    merged.nodes().put(key, l.copy());
                    bump(auto, "nodesAdded");
                    continue;
                }
                // add/add divergence
                conflicts.add(new Conflict("node:" + key, ConflictKind.NODE, key,
                        "节点 '" + key + "' 被两侧同时新增但内容不同",
                        describeNode(l), describeNode(r), null, true, false));
                merged.nodes().put(key, l.copy());
                continue;
            }
            // present in base
            if (l == null && r == null) {
                // deleted on both
                continue;
            }
            if (l == null) {
                if (b.contentEquals(r)) {
                    // deleted on the left only
                    continue;
                }
                conflicts.add(new Conflict("node:" + key, ConflictKind.NODE, key,
                        "节点 '" + key + "' 在左侧被删除，右侧做了修改",
                        "删除节点", describeNode(r), null, true, false));
                continue;
            }
            if (r == null) {
                if (b.contentEquals(l)) {
                    // deleted on the right only
                    continue;
                }
                conflicts.add(new Conflict("node:" + key, ConflictKind.NODE, key,
                        "节点 '" + key + "' 在右侧被删除，左侧做了修改",
                        describeNode(l), "删除节点", null, true, false));
                merged.nodes().put(key, l.copy());
                continue;
            }
            // present everywhere: field/edge level merge
            mergeNodeFields(key, b, l, r, merged, conflicts, auto);
        }
    }

    private void mergeNodeFields(String key, PackageNode b, PackageNode l, PackageNode r,
                                 Lockfile merged, List<Conflict> conflicts,
                                 Map<String, Integer> auto) {
        PackageNode tentative = new PackageNode(l.name(), l.version(),
                l.source(), l.integrity(), l.condition(), new LinkedHashMap<>());
        boolean anyConflict = false;

        anyConflict |= scalarField(key, "source", b.source(), l.source(), r.source(),
                tentative, conflicts, Field.SOURCE);
        anyConflict |= scalarField(key, "integrity", b.integrity(), l.integrity(), r.integrity(),
                tentative, conflicts, Field.INTEGRITY);
        anyConflict |= scalarField(key, "condition", b.condition(), l.condition(), r.condition(),
                tentative, conflicts, Field.CONDITION);

        // edges
        Set<String> children = new LinkedHashSet<>();
        children.addAll(b.edges().keySet());
        children.addAll(l.edges().keySet());
        children.addAll(r.edges().keySet());
        for (String child : new ArrayList<>(children)) {
            Edge eb = b.edges().get(child);
            Edge el = l.edges().get(child);
            Edge er = r.edges().get(child);
            if (Objects.equals(el, er)) {
                if (el != null) tentative.edges().put(child, el);
                bump(auto, "edges");
                continue;
            }
            if (Objects.equals(eb, el)) {
                if (er != null) tentative.edges().put(child, er);
                bump(auto, "edges");
                continue;
            }
            if (Objects.equals(eb, er)) {
                if (el != null) tentative.edges().put(child, el);
                bump(auto, "edges");
                continue;
            }
            conflicts.add(new Conflict("edge:" + key + ":" + child, ConflictKind.EDGE,
                    key + " -> " + child,
                    "边 '" + key + " -> " + child + "' 两侧修改不一致",
                    describeEdge(el), describeEdge(er), null, true, false));
            if (el != null) tentative.edges().put(child, el);
            anyConflict = true;
        }
        merged.nodes().put(key, tentative);
    }

    private enum Field { SOURCE, INTEGRITY, CONDITION }

    private boolean scalarField(String key, String fieldName, String b, String l, String r,
                                PackageNode target, List<Conflict> conflicts, Field field) {
        ThreeWay.Result res = ThreeWay.merge(emptyToNull(b), emptyToNull(l), emptyToNull(r));
        String value = res.value() == null ? "" : (String) res.value();
        switch (field) {
            case SOURCE -> {
                target.setSource(value);
            }
            case INTEGRITY -> target.setIntegrity(value);
            case CONDITION -> target.setCondition(value);
        }
        if (res.conflict()) {
            String id = "field:" + key + ":" + fieldName;
            conflicts.add(new Conflict(id, ConflictKind.FIELD, key + "#" + fieldName,
                    "节点 '" + key + "' 的 " + fieldName + " 两侧修改不一致",
                    l == null ? "(空)" : l, r == null ? "(空)" : r, null, true, false));
            return true;
        }
        return false;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String describeNode(PackageNode n) {
        StringBuilder sb = new StringBuilder();
        sb.append("source=").append(n.source())
                .append(" integrity=").append(n.integrity());
        if (!n.condition().isEmpty()) {
            sb.append(" when ").append(n.condition());
        }
        if (!n.edges().isEmpty()) {
            sb.append(" requires ").append(String.join(",", n.edges().keySet()));
        }
        return sb.toString();
    }

    private static String describeEdge(Edge e) {
        if (e == null) {
            return "删除引用";
        }
        String s = e.child() + "@" + e.spec();
        if (!e.condition().isEmpty()) {
            s += " when " + e.condition();
        }
        return s;
    }

    // ------------------------------------------------------------- verdicts

    private void applyVerdicts(Lockfile merged, List<Conflict> conflicts,
                               Map<String, Side> verdicts) {
        for (Conflict c : conflicts) {
            Side side = verdicts.get(c.id());
            if (side == null) {
                continue;
            }
            applyOne(merged, c, side);
            int idx = findConflict(conflicts, c.id());
            Conflict updated = conflicts.get(idx).withRecompute(side, true, false);
            conflicts.set(idx, updated);
        }
    }

    private int findConflict(List<Conflict> conflicts, String id) {
        for (int i = 0; i < conflicts.size(); i++) {
            if (conflicts.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException("missing conflict " + id);
    }

    private void applyOne(Lockfile merged, Conflict c, Side side) {
        switch (c.kind()) {
            case ROOT -> applyRootVerdict(merged, c, side);
            case NODE -> applyNodeVerdict(merged, c, side);
            case FIELD -> applyFieldVerdict(merged, c, side);
            case EDGE -> applyEdgeVerdict(merged, c, side);
            case INTEGRITY -> applyIntegrityVerdict(merged, c, side);
        }
    }

    private void applyRootVerdict(Lockfile merged, Conflict c, Side side) {
        String name = c.subject();
        RootRef chosen = (side == Side.LEFT ? left : right).roots().stream()
                .filter(r -> r.name().equals(name)).findFirst().orElse(null);
        merged.roots().removeIf(r -> r.name().equals(name));
        if (chosen != null) {
            merged.roots().add(chosen);
        }
    }

    private void applyNodeVerdict(Lockfile merged, Conflict c, Side side) {
        String key = c.subject();
        PackageNode chosen = (side == Side.LEFT ? left : right).nodes().get(key);
        merged.nodes().remove(key);
        if (chosen != null) {
            merged.nodes().put(key, chosen.copy());
        }
    }

    private void applyFieldVerdict(Lockfile merged, Conflict c, Side side) {
        int hash = c.subject().indexOf('#');
        String key = c.subject().substring(0, hash);
        String field = c.subject().substring(hash + 1);
        PackageNode target = merged.nodes().get(key);
        if (target == null) {
            return;
        }
        PackageNode chosen = (side == Side.LEFT ? left : right).nodes().get(key);
        if (chosen == null) {
            return;
        }
        switch (field) {
            case "source" -> target.setSource(chosen.source());
            case "integrity" -> target.setIntegrity(chosen.integrity());
            case "condition" -> target.setCondition(chosen.condition());
            default -> throw new IllegalStateException(field);
        }
    }

    private void applyEdgeVerdict(Lockfile merged, Conflict c, Side side) {
        String subject = c.subject();
        int arrow = subject.indexOf(" -> ");
        String key = subject.substring(0, arrow);
        String child = subject.substring(arrow + 4);
        PackageNode target = merged.nodes().get(key);
        if (target == null) {
            return;
        }
        PackageNode chosen = (side == Side.LEFT ? left : right).nodes().get(key);
        target.edges().remove(child);
        if (chosen != null && chosen.edges().containsKey(child)) {
            target.edges().put(child, chosen.edges().get(child));
        }
    }

    private void applyIntegrityVerdict(Lockfile merged, Conflict c, Side side) {
        int sep = c.subject().indexOf('|');
        String source = c.subject().substring(0, sep);
        String version = c.subject().substring(sep + 1);
        String digest = side == Side.LEFT ? c.leftOption() : c.rightOption();
        for (PackageNode n : merged.nodes().values()) {
            if (n.source().equals(source) && n.version().equals(version)) {
                n.setIntegrity(digest);
            }
        }
    }

    // ------------------------------------------------------------ integrity

    private record Group(String source, String version) {}

    private void addIntegrityConflicts(Lockfile merged, List<Conflict> conflicts) {
        Map<Group, List<PackageNode>> groups = new LinkedHashMap<>();
        for (PackageNode n : merged.nodes().values()) {
            groups.computeIfAbsent(new Group(n.source(), n.version()), k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<Group, List<PackageNode>> e : groups.entrySet()) {
            Set<String> digests = new LinkedHashSet<>();
            for (PackageNode n : e.getValue()) {
                digests.add(n.integrity());
            }
            if (digests.size() > 1) {
                Group g = e.getKey();
                List<String> sorted = new ArrayList<>(digests);
                sorted.sort(String::compareTo);
                String id = "integrity:" + g.source() + "|" + g.version();
                String who = e.getValue().stream().map(PackageNode::key)
                        .sorted().collect(java.util.stream.Collectors.joining(", "));
                conflicts.add(new Conflict(id, ConflictKind.INTEGRITY,
                        g.source() + "|" + g.version(),
                        "来源 '" + g.source() + "' 版本 '" + g.version()
                                + "' 的摘要不一致（节点 " + who + "）",
                        sorted.get(0), sorted.get(sorted.size() - 1), null, true, false));
            }
        }
    }

    private List<String> checkIntegrity(Lockfile merged) {
        List<String> issues = new ArrayList<>();
        Map<Group, List<PackageNode>> groups = new LinkedHashMap<>();
        for (PackageNode n : merged.nodes().values()) {
            groups.computeIfAbsent(new Group(n.source(), n.version()), k -> new ArrayList<>()).add(n);
        }
        for (Map.Entry<Group, List<PackageNode>> e : groups.entrySet()) {
            Set<String> digests = new LinkedHashSet<>();
            for (PackageNode n : e.getValue()) {
                digests.add(n.integrity());
            }
            if (digests.size() > 1) {
                Group g = e.getKey();
                String who = e.getValue().stream().map(PackageNode::key)
                        .sorted().collect(java.util.stream.Collectors.joining(", "));
                issues.add("来源 '" + g.source() + "' 版本 '" + g.version()
                        + "' 的摘要不一致（节点 " + who + "）");
            }
        }
        return issues;
    }

    // --------------------------------------------------------- reachability

    private Set<String> unionReachable(Lockfile merged, List<Conflict> conflicts) {
        // Possibility graph: resolved conflicts use the chosen side only;
        // unresolved conflicts union both sides' roots/nodes/edges.
        Lockfile union = new Lockfile();
        for (RootRef r : merged.roots()) {
            union.roots().add(r);
        }
        for (PackageNode n : merged.nodes().values()) {
            union.nodes().put(n.key(), n.copy());
        }
        for (Conflict c : conflicts) {
            if (c.resolution() != null) {
                continue;
            }
            switch (c.kind()) {
                case ROOT -> {
                    String name = c.subject();
                    RootRef rl = rootByName(left, name);
                    RootRef rr = rootByName(right, name);
                    if (rl != null) union.roots().add(rl);
                    if (rr != null) union.roots().add(rr);
                }
                case NODE -> {
                    PackageNode l = left.nodes().get(c.subject());
                    PackageNode r = right.nodes().get(c.subject());
                    if (l != null) union.nodes().put(l.key(), l.copy());
                    if (r != null) union.nodes().put(r.key(), r.copy());
                }
                case FIELD -> {
                    // node already present in merged; no additional edges/roots
                }
                case EDGE -> {
                    int arrow = c.subject().indexOf(" -> ");
                    String key = c.subject().substring(0, arrow);
                    String child = c.subject().substring(arrow + 4);
                    addUnionEdge(union, left, key, child);
                    addUnionEdge(union, right, key, child);
                }
                case INTEGRITY -> {
                    // same nodes, differing digests; identity unaffected
                }
            }
        }
        return reachableFromRoots(union);
    }

    private void addUnionEdge(Lockfile union, Lockfile side, String key, String child) {
        PackageNode sideNode = side.nodes().get(key);
        PackageNode unionNode = union.nodes().get(key);
        if (sideNode == null || unionNode == null) {
            return;
        }
        Edge e = sideNode.edges().get(child);
        if (e != null) {
            unionNode.edges().put(child, e);
        }
    }

    private Set<String> reachableFromRoots(Lockfile lf) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        for (RootRef r : lf.roots()) {
            String k = r.targetKey();
            if (lf.nodes().containsKey(k) && visited.add(k)) {
                queue.add(k);
            }
        }
        for (int head = 0; head < queue.size(); head++) {
            PackageNode n = lf.nodes().get(queue.get(head));
            for (Edge e : n.edges().values()) {
                String k = e.child() + "@" + e.spec();
                if (lf.nodes().containsKey(k) && visited.add(k)) {
                    queue.add(k);
                }
            }
        }
        return visited;
    }

    private List<Conflict> markActivity(Lockfile merged, List<Conflict> conflicts,
                                        List<DanglingRef> dangling) {
        Set<String> unionReach = unionReachable(merged, conflicts);
        Set<String> danglingParentKeys = new LinkedHashSet<>();
        for (DanglingRef d : dangling) {
            if (!d.from().startsWith("root:")) {
                danglingParentKeys.add(d.from());
            }
        }
        List<Conflict> result = new ArrayList<>();
        for (Conflict c : conflicts) {
            boolean active = switch (c.kind()) {
                case ROOT -> true;
                case NODE -> unionReach.contains(c.subject());
                case INTEGRITY -> integrityGroupReachable(merged, unionReach, c.subject());
                case FIELD -> {
                    int hash = c.subject().indexOf('#');
                    yield unionReach.contains(c.subject().substring(0, hash));
                }
                case EDGE -> {
                    int arrow = c.subject().indexOf(" -> ");
                    yield unionReach.contains(c.subject().substring(0, arrow));
                }
            };
            // An edge/root conflict that creates a dangling reference stays visible.
            boolean danglingFlag = switch (c.kind()) {
                case ROOT -> dangling.stream().anyMatch(
                        d -> d.from().equals("root:" + c.subject()) && c.resolution() != null);
                case EDGE -> danglingParentKeys.contains(parentKey(c.subject()))
                        && c.resolution() != null;
                default -> false;
            };
            if (danglingFlag) {
                active = true;
            }
            if (c.active() != active || c.danglingAfterResolution() != danglingFlag) {
                result.add(c.withRecompute(c.resolution(), active, danglingFlag));
            } else {
                result.add(c);
            }
        }
        result.sort(Comparator.comparing(Conflict::id));
        return result;
    }

    private boolean integrityGroupReachable(Lockfile merged, Set<String> unionReach, String subject) {
        int sep = subject.indexOf('|');
        String source = subject.substring(0, sep);
        String version = subject.substring(sep + 1);
        for (PackageNode n : merged.nodes().values()) {
            if (n.source().equals(source) && n.version().equals(version)
                    && unionReach.contains(n.key())) {
                return true;
            }
        }
        return false;
    }

    private static String parentKey(String edgeSubject) {
        int arrow = edgeSubject.indexOf(" -> ");
        return edgeSubject.substring(0, arrow);
    }

    // --------------------------------------------------------- dangling/prune

    private List<DanglingRef> findDangling(Lockfile merged) {
        List<DanglingRef> result = new ArrayList<>();
        for (RootRef r : merged.roots()) {
            if (!merged.nodes().containsKey(r.targetKey())) {
                result.add(new DanglingRef("root:" + r.name(), r.targetKey(),
                        "根依赖 '" + r.name() + "' 指向缺失节点 '" + r.targetKey() + "'"));
            }
        }
        for (PackageNode n : merged.nodes().values()) {
            for (Edge e : n.edges().values()) {
                String target = e.child() + "@" + e.spec();
                if (!merged.nodes().containsKey(target)) {
                    result.add(new DanglingRef(n.key(), target,
                            "包 '" + n.key() + "' 的引用 '" + target + "' 无对应节点"));
                }
            }
        }
        result.sort(Comparator.comparing(DanglingRef::from).thenComparing(DanglingRef::target));
        return result;
    }

    private List<RemovedNode> explainRemovals(Lockfile merged, Set<String> reachable,
                                              Set<String> mergedRootNames) {
        List<RemovedNode> removed = new ArrayList<>();
        List<String> keys = new ArrayList<>(merged.nodes().keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (reachable.contains(key)) {
                continue;
            }
            removed.add(new RemovedNode(key,
                    removalReason(merged, key, reachable, mergedRootNames)));
        }
        return removed;
    }

    private String removalReason(Lockfile merged, String key, Set<String> reachable,
                                 Set<String> mergedRootNames) {
        PackageNode node = merged.nodes().get(key);
        // Referenced only by parents that are themselves unreachable?
        List<String> liveInbound = new ArrayList<>();
        List<String> deadInbound = new ArrayList<>();
        for (PackageNode parent : merged.nodes().values()) {
            for (Edge e : parent.edges().values()) {
                if ((e.child() + "@" + e.spec()).equals(key)) {
                    if (reachable.contains(parent.key())) {
                        liveInbound.add(parent.key());
                    } else {
                        deadInbound.add(parent.key());
                    }
                }
            }
        }
        boolean rootNameAbsent = !mergedRootNames.contains(node.name());
        String goneRoot = rootNameAbsent ? findInputRootName(node.name()) : null;
        if (goneRoot != null) {
            return "根依赖 '" + goneRoot + "' 在合并中被删除，节点不再从任何根可达";
        }
        if (!liveInbound.isEmpty()) {
            // Shouldn't happen (would be reachable), keep a safe message.
            return "可达性清理：未被最终闭包保留";
        }
        if (!deadInbound.isEmpty()) {
            return "仅被同样不可达的父节点 " + String.join(", ", deadInbound)
                    + " 引用，随上游子图一并清理";
        }
        if (goneRoot != null) {
            return "曾属于已删除根依赖 '" + goneRoot + "' 的子树，无其他父节点引用";
        }
        return "无任何根或可达父节点引用，属于孤立节点";
    }

    private String findInputRootName(String nodeName) {
        for (RootRef r : base.roots()) {
            if (r.name().equals(nodeName)) {
                return nodeName;
            }
        }
        for (RootRef r : left.roots()) {
            if (r.name().equals(nodeName)) {
                return nodeName;
            }
        }
        for (RootRef r : right.roots()) {
            if (r.name().equals(nodeName)) {
                return nodeName;
            }
        }
        return null;
    }

    private Lockfile prune(Lockfile merged, Set<String> reachable) {
        Lockfile out = new Lockfile();
        out.roots().addAll(merged.roots());
        for (String key : reachable) {
            PackageNode n = merged.nodes().get(key);
            PackageNode copy = new PackageNode(n.name(), n.version(), n.source(),
                    n.integrity(), n.condition(), new LinkedHashMap<>());
            for (Map.Entry<String, Edge> e : n.edges().entrySet()) {
                String target = e.getValue().child() + "@" + e.getValue().spec();
                if (reachable.contains(target)) {
                    copy.edges().put(e.getKey(), e.getValue());
                }
            }
            out.nodes().put(key, copy);
        }
        return out;
    }
}
