package dev.lockmerge.merge;

import dev.lockmerge.model.Diagnostic;
import dev.lockmerge.model.LockDocument;
import dev.lockmerge.model.Node;
import dev.lockmerge.model.Ref;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Three-way semantic merge for lock graphs.
 *
 * <p>The engine works purely from parsed documents plus the SHA-256 fingerprint
 * triple of the original text. Re-running {@link #compute} with a set of
 * decisions produces a fresh result: decisions whose bound fingerprints no not
 * match the inputs are reported as stale and never applied.
 */
public final class MergeEngine {

    /** One side's parsed document plus diagnostics. */
    public record SideInput(String fingerprint, LockDocument document,
                            List<Diagnostic> parseDiagnostics, Validator.Report validation) {
    }

    public record Inputs(SideInput base, SideInput left, SideInput right) {
        public List<String> fingerprints() {
            return List.of(base.fingerprint(), left.fingerprint(), right.fingerprint());
        }
    }

    /** Result of one recompute pass. */
    public static final class Result {
        public final List<Conflict> conflicts;
        public final List<Decision> appliedDecisions;
        public final List<String> staleDecisionIds;
        public final List<RemovedNode> removed;
        public final List<Ref> dangling;
        public final List<String> integrityNotes;
        public final LockDocument optimisticGraph;
        public final LockDocument resolvedGraph;
        public final boolean resolvable;
        public final List<String> resolutionErrors;

        Result(List<Conflict> conflicts, List<Decision> appliedDecisions,
               List<String> staleDecisionIds, List<RemovedNode> removed, List<Ref> dangling,
               List<String> integrityNotes, LockDocument optimisticGraph,
               LockDocument resolvedGraph, boolean resolvable, List<String> resolutionErrors) {
            this.conflicts = List.copyOf(conflicts);
            this.appliedDecisions = List.copyOf(appliedDecisions);
            this.staleDecisionIds = List.copyOf(staleDecisionIds);
            this.removed = List.copyOf(removed);
            this.dangling = List.copyOf(dangling);
            this.integrityNotes = List.copyOf(integrityNotes);
            this.optimisticGraph = optimisticGraph;
            this.resolvedGraph = resolvedGraph;
            this.resolvable = resolvable;
            this.resolutionErrors = List.copyOf(resolutionErrors);
        }

        public boolean canPublish() {
            return conflicts.isEmpty() && dangling.isEmpty()
                    && resolutionErrors.isEmpty() && resolvable;
        }
    }

    private MergeEngine() {
    }

    static final Comparator<Ref> REF_ORDER =
            Comparator.comparing(Ref::name)
                    .thenComparing(Ref::version)
                    .thenComparing(Ref::condition, Comparator.nullsFirst(Comparator.naturalOrder()));

    public static List<Ref> sortedRefs(List<Ref> refs) {
        return refs.stream().sorted(REF_ORDER).toList();
    }

    public static boolean sameRef(Ref a, Ref b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.name(), b.name())
                && Objects.equals(a.version(), b.version())
                && Objects.equals(a.condition(), b.condition());
    }

    public static Result compute(Inputs inputs, List<Decision> decisions) {
        List<String> resolutionErrors = new ArrayList<>();
        List<Decision> applied = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (Decision decision : decisions) {
            if (decision.binds(inputs.fingerprints())) {
                applied.add(decision);
            } else {
                stale.add(decision.conflictId());
            }
        }
        Map<String, Decision> decisionById = new LinkedHashMap<>();
        for (Decision decision : applied) {
            decisionById.put(decision.conflictId(), decision);
        }

        Map<String, Node> baseNodes = inputs.base().document().nodeIndex();
        Map<String, Node> leftNodes = inputs.left().document().nodeIndex();
        Map<String, Node> rightNodes = inputs.right().document().nodeIndex();

        // ---- Integrity conflicts across sides ---------------------------
        // Detected BEFORE node payload merging: same source coordinate +
        // version carrying different digests between sides is an integrity
        // conflict even when the nodes are otherwise identical.
        List<Conflict> integrityConflicts = new ArrayList<>();
        Map<String, String> digestChoice = new HashMap<>();
        List<CrossDigest> crossDigests = new ArrayList<>();
        detectCrossSideDigest(inputs, crossDigests);
        for (CrossDigest issue : crossDigests) {
            String conflictId = "integrity:" + issue.source() + "|" + issue.version();
            integrityConflicts.add(new Conflict(conflictId, Conflict.Type.INTEGRITY,
                    "来源 " + issue.source() + " 版本 " + issue.version() + " 摘要冲突",
                    "同一来源坐标与版本在两侧出现了不同的 sha256 摘要"
                            + "（left=" + issue.leftDigest() + "，right=" + issue.rightDigest()
                            + "）",
                    null, issue.leftDigest(), issue.rightDigest()));
            Decision decision = decisionById.get(conflictId);
            if (decision != null) {
                String chosen = switch (decision.choice()) {
                    case Decision.LEFT -> issue.leftDigest();
                    case Decision.RIGHT -> issue.rightDigest();
                    default -> null;
                };
                if (chosen == null) {
                    resolutionErrors.add(conflictId + ": 完整性冲突只能选择 LEFT 或 RIGHT");
                } else {
                    digestChoice.put(issue.source() + "|" + issue.version(), chosen);
                }
            }
        }

        // ---- Node-level three-way merge ---------------------------------
        List<Conflict> conflicts = new ArrayList<>(integrityConflicts);
        Map<String, Node> mergedNodes = new LinkedHashMap<>();
        Set<String> deletedNodeIds = new LinkedHashSet<>();
        Set<String> explicitlyDeletedIds = new LinkedHashSet<>();
        Set<String> oneSideDeleted = new LinkedHashSet<>();

        Set<String> allNodeIds = new LinkedHashSet<>();
        allNodeIds.addAll(baseNodes.keySet());
        allNodeIds.addAll(leftNodes.keySet());
        allNodeIds.addAll(rightNodes.keySet());

        for (String id : sortedIds(allNodeIds)) {
            Node b = baseNodes.get(id);
            Node l = leftNodes.get(id);
            Node r = rightNodes.get(id);
            Node effectiveL = applyDigestChoices(l, digestChoice);
            Node effectiveR = applyDigestChoices(r, digestChoice);
            Node effectiveB = applyDigestChoices(b, digestChoice);
            if (effectiveL != null && effectiveR != null
                    && semanticEquals(effectiveL, effectiveR)) {
                mergedNodes.put(id, effectiveL);
            } else if (effectiveL != null && semanticEquals(effectiveL, effectiveB)
                    && effectiveR != null) {
                mergedNodes.put(id, effectiveR);
            } else if (effectiveR != null && semanticEquals(effectiveR, effectiveB)
                    && effectiveL != null) {
                mergedNodes.put(id, effectiveL);
            } else if (effectiveB == null && effectiveL == null && effectiveR == null) {
                continue;
            } else if (effectiveB != null && effectiveL == null && effectiveR == null) {
                deletedNodeIds.add(id);
            } else if ((effectiveL == null) != (effectiveR == null)) {
                // Exactly one side removed this id; the other retained it.
                Node survivor = effectiveL != null ? effectiveL : effectiveR;
                // "Modified" means the node's own payload (source/integrity/
                // platform) changed. A differing child list alone is a
                // transitive upgrade and must not turn the other side's root
                // deletion into a node conflict.
                boolean payloadModified = effectiveB != null
                        && (!Objects.equals(survivor.source(), effectiveB.source())
                            || !Objects.equals(survivor.integrity(), effectiveB.integrity())
                            || !Objects.equals(survivor.platform(), effectiveB.platform()));
                if (effectiveB == null || !payloadModified) {
                    // Added on one side only, or the survivor is identical to
                    // base while the other side deleted it: deletion wins.
                    if (effectiveB == null) {
                        mergedNodes.put(id, survivor);
                    } else {
                        deletedNodeIds.add(id);
                    }
                    continue;
                }
                String side = effectiveL != null ? "left" : "right";
                String conflictId = "node:" + id;
                conflicts.add(new Conflict(conflictId, Conflict.Type.NODE_DELETE,
                        "包节点 " + id + " 被一侧删除而另一侧保留",
                        "一侧删除了 " + id + "，" + side + " 侧仍保留并修改了该节点",
                        effectiveB, effectiveL, effectiveR));
                oneSideDeleted.add(id);
                Decision decision = decisionById.get(conflictId);
                String choice = decision == null ? null : decision.choice();
                boolean explicitDelete =
                        (Decision.LEFT.equals(choice) && effectiveL == null)
                        || (Decision.RIGHT.equals(choice) && effectiveR == null);
                if (explicitDelete) {
                    deletedNodeIds.add(id);
                    explicitlyDeletedIds.add(id);
                } else if (Decision.LEFT.equals(choice) && effectiveL != null) {
                    mergedNodes.put(id, effectiveL);
                } else if (Decision.RIGHT.equals(choice) && effectiveR != null) {
                    mergedNodes.put(id, effectiveR);
                } else if (Decision.BASE.equals(choice)) {
                    mergedNodes.put(id, effectiveB);
                } else {
                    mergedNodes.put(id, survivor);
                }
            } else if (effectiveL != null && effectiveR != null) {
                // Both changed in different ways: modify-modify conflict. If the
                // only difference is the digest of a coordinate already covered
                // by an INTEGRITY conflict, do not double-report it.
                String coordKey = effectiveL.source() + "|" + effectiveL.version();
                boolean integrityCovered = crossDigests.stream()
                        .anyMatch(c -> (c.source() + "|" + c.version()).equals(coordKey));
                boolean onlyDigestDiff = Objects.equals(effectiveL.name(), effectiveR.name())
                        && Objects.equals(effectiveL.version(), effectiveR.version())
                        && Objects.equals(effectiveL.source(), effectiveR.source())
                        && Objects.equals(effectiveL.platform(), effectiveR.platform())
                        && sameRefList(sortedRefs(effectiveL.children()),
                                sortedRefs(effectiveR.children()));
                if (integrityCovered && onlyDigestDiff) {
                    mergedNodes.put(id, effectiveL);
                    continue;
                }
                String conflictId = "node:" + id;
                conflicts.add(new Conflict(conflictId, Conflict.Type.NODE_MODIFY,
                        "包节点 " + id + " 被两侧分别修改",
                        describeNodeDiff(effectiveB, effectiveL, effectiveR),
                        effectiveB, effectiveL, effectiveR));
                Decision decision = decisionById.get(conflictId);
                Node picked;
                if (decision == null) {
                    picked = effectiveL;
                } else if (Decision.LEFT.equals(decision.choice())) {
                    picked = effectiveL;
                } else if (Decision.RIGHT.equals(decision.choice())) {
                    picked = effectiveR;
                } else if (Decision.BASE.equals(decision.choice())) {
                    picked = effectiveB;
                } else {
                    resolutionErrors.add(conflictId + ": 未知选项 " + decision.choice());
                    picked = effectiveL;
                }
                if (picked == null) {
                    deletedNodeIds.add(id);
                    explicitlyDeletedIds.add(id);
                } else {
                    mergedNodes.put(id, picked);
                }
            } else {
                // Defensive fallback: retain whichever side has the node.
                Node survivor = effectiveL != null ? effectiveL : effectiveR;
                if (survivor == null) {
                    deletedNodeIds.add(id);
                } else {
                    mergedNodes.put(id, survivor);
                }
            }
        }

        // ---- Edge three-way merge (roots + per-parent children) --------
        Map<String, List<Ref>> baseEdges = edgeMap(inputs.base().document());
        Map<String, List<Ref>> leftEdges = edgeMap(inputs.left().document());
        Map<String, List<Ref>> rightEdges = edgeMap(inputs.right().document());

        Set<String> edgeOwners = new LinkedHashSet<>();
        edgeOwners.add("root");
        edgeOwners.addAll(baseEdges.keySet());
        edgeOwners.addAll(leftEdges.keySet());
        edgeOwners.addAll(rightEdges.keySet());

        Map<String, List<Ref>> optimisticEdges = new LinkedHashMap<>();
        Map<String, List<Ref>> resolvedEdges = new LinkedHashMap<>();

        for (String owner : edgeOwners.stream().sorted().toList()) {
            EdgeMerge edgeMerge = mergeEdges(owner,
                    baseEdges.getOrDefault(owner, List.of()),
                    leftEdges.getOrDefault(owner, List.of()),
                    rightEdges.getOrDefault(owner, List.of()),
                    conflicts, decisionById, resolutionErrors);
            optimisticEdges.put(owner, edgeMerge.optimistic);
            resolvedEdges.put(owner, edgeMerge.resolved);
        }

        LockDocument optimisticGraph = buildGraph(optimisticEdges, mergedNodes, deletedNodeIds);
        if (!explicitlyDeletedIds.isEmpty()) {
            Map<String, List<Ref>> stripped = new LinkedHashMap<>(resolvedEdges);
            stripped.replaceAll((owner, refs) -> refs.stream()
                    .filter(ref -> !explicitlyDeletedIds.contains(
                            ref.name() + "@" + ref.version()))
                    .toList());
            resolvedEdges = stripped;
        }
        LockDocument resolvedGraph = buildGraph(resolvedEdges, mergedNodes, deletedNodeIds);

        // ---- Reachability cleanup ---------------------------------------
        Reachability optimisticReach = reachable(optimisticGraph);
        Reachability resolvedReach = reachable(resolvedGraph);

        List<RemovedNode> removed = new ArrayList<>();
        Set<String> explainedIds = new LinkedHashSet<>();

        // Nodes deleted by an explicit or suppressed delete outcome.
        for (String id : sortedIds(deletedNodeIds)) {
            boolean rootInLeft = inputs.left().document().roots().stream()
                    .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
            boolean rootInRight = inputs.right().document().roots().stream()
                    .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
            String reason;
            if (!rootInLeft || !rootInRight) {
                String deletingSide = !rootInLeft ? "left" : "right";
                reason = "被 " + deletingSide + " 侧删除";
            } else {
                reason = "三方合并裁决删除该节点";
            }
            removed.add(new RemovedNode(id, reason));
            explainedIds.add(id);
        }

        Set<String> allMergedIds = new LinkedHashSet<>(mergedNodes.keySet());
        for (String id : optimisticGraph.nodeIndex().keySet()) {
            allMergedIds.add(id);
        }
        for (String id : sortedIds(allMergedIds)) {
            Node node = mergedNodes.get(id);
            if (node == null || explainedIds.contains(id)) {
                continue;
            }
            boolean reachableOptimistic = optimisticReach.live.contains(id);
            boolean rootInLeft = inputs.left().document().roots().stream()
                    .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
            boolean rootInRight = inputs.right().document().roots().stream()
                    .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
            boolean rootInBase = inputs.base().document().roots().stream()
                    .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
            if (!reachableOptimistic) {
                String reason;
                if (rootInBase && !rootInLeft && !rootInRight) {
                    reason = "根依赖在两侧均被删除，且无其他根可达";
                } else if (rootInBase && (!rootInLeft || !rootInRight)) {
                    String deletingSide = !rootInLeft ? "left" : "right";
                    reason = "根依赖被 " + deletingSide
                            + " 侧删除；另一分支仅升级传递节点，不作为冲突";
                } else {
                    reason = "合并后无任何根可达该节点";
                }
                removed.add(new RemovedNode(id, reason));
                explainedIds.add(id);
            }
        }

        LockDocument cleanedOptimistic = prune(optimisticGraph, optimisticReach.live);
        LockDocument cleanedResolved = prune(resolvedGraph, resolvedReach.live);

        // A conflict disappears from the pending list once a decision bound to
        // the current fingerprint triple has been applied. NODE_DELETE conflicts
        // for nodes unreachable in both graphs are suppressed too.
        Set<String> decidedIds = decisionById.keySet();
        Set<String> liveInBoth = new LinkedHashSet<>(optimisticReach.live);
        liveInBoth.retainAll(resolvedReach.live);
        List<Conflict> activeConflicts = conflicts.stream()
                .filter(c -> !decidedIds.contains(c.id()))
                .filter(c -> {
                    if (c.type() != Conflict.Type.NODE_DELETE) {
                        return true;
                    }
                    String id = c.id().substring("node:".length());
                    // A delete/modify candidate is a genuine conflict only when
                    // the surviving node is reachable in the optimistic graph.
                    // When the other branch only upgraded transitive nodes under
                    // a root that one side deleted, the survivor is unreachable
                    // and reaches the output via cleanup, not as a conflict.
                    // Genuine delete/modify conflict only when the retaining
                    // side can still reach the node from its own roots. If the
                    // retainer lost the only rooting path (the other branch
                    // deleted that root), the survivor is cleaned up, not fought.
                    boolean rootedLeft = inputs.left().document().roots().stream()
                            .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
                    boolean rootedRight = inputs.right().document().roots().stream()
                            .anyMatch(r -> (r.name() + "@" + r.version()).equals(id));
                    return rootedLeft || rootedRight
                            || optimisticReach.live.contains(id)
                            || resolvedReach.live.contains(id);
                })
                .toList();

        // ---- Dangling references in the merged graph --------------------
        List<Ref> dangling = new ArrayList<>();
        Set<String> danglingKeys = new HashSet<>();
        Map<String, Node> cleanedIndex = cleanedResolved.nodeIndex();
        for (Ref ref : cleanedResolved.roots()) {
            if (!cleanedIndex.containsKey(ref.name() + "@" + ref.version())
                    && danglingKeys.add(ref.key())) {
                dangling.add(ref);
            }
        }
        for (Node node : cleanedResolved.nodes()) {
            for (Ref ref : node.children()) {
                if (!cleanedIndex.containsKey(ref.name() + "@" + ref.version())
                        && danglingKeys.add(ref.key())) {
                    dangling.add(ref);
                }
            }
        }

        boolean resolvable = true;
        return new Result(activeConflicts, applied, stale, removed, dangling,
                List.of(), cleanedOptimistic, cleanedResolved, resolvable,
                resolutionErrors);
    }

    private static LockDocument prune(LockDocument document, Set<String> live) {
        List<Ref> roots = document.roots().stream()
                .filter(r -> live.contains(r.name() + "@" + r.version()))
                .toList();
        List<Node> nodes = document.nodes().stream()
                .filter(n -> live.contains(n.id()))
                .toList();
        return new LockDocument(roots, nodes);
    }

    private record Reachability(Set<String> live) {
    }

    private static Reachability reachable(LockDocument document) {
        Map<String, Node> index = document.nodeIndex();
        LinkedHashSet<String> live = new LinkedHashSet<>();
        ArrayList<String> queue = new ArrayList<>();
        for (Ref root : document.roots()) {
            String id = root.name() + "@" + root.version();
            if (index.containsKey(id) && live.add(id)) {
                queue.add(id);
            }
        }
        for (int head = 0; head < queue.size(); head++) {
            Node node = index.get(queue.get(head));
            for (Ref child : node.children()) {
                String id = child.name() + "@" + child.version();
                if (index.containsKey(id) && live.add(id)) {
                    queue.add(id);
                }
            }
        }
        return new Reachability(live);
    }

    private static Map<String, List<Ref>> edgeMap(LockDocument document) {
        Map<String, List<Ref>> map = new LinkedHashMap<>();
        map.put("root", sortedRefs(document.roots()));
        for (Node node : document.nodes()) {
            map.put(node.id(), sortedRefs(node.children()));
        }
        return map;
    }

    private record EdgeMerge(List<Ref> optimistic, List<Ref> resolved) {
    }

    /**
     * Three-way merge of the edge set owned by one parent. Edges are identified
     * by the full Ref key (name, exact version, normalized condition).
     *
     * <p>A divergent pair is detected when both sides independently add an edge
     * to the same child name (same parent, no base edge with that child name):
     * that is flagged as EDGE_DIVERGE with LEFT/RIGHT/BOTH choices.
     */
    private static EdgeMerge mergeEdges(String owner,
                                        List<Ref> base,
                                        List<Ref> left,
                                        List<Ref> right,
                                        List<Conflict> conflicts,
                                        Map<String, Decision> decisions,
                                        List<String> resolutionErrors) {
        Map<String, Ref> baseByKey = indexByKey(base);
        Map<String, Ref> leftByKey = indexByKey(left);
        Map<String, Ref> rightByKey = indexByKey(right);

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(baseByKey.keySet());
        allKeys.addAll(leftByKey.keySet());
        allKeys.addAll(rightByKey.keySet());

        // Classical three-way per-key outcome (ignoring cross-key divergences).
        Set<String> mergedKeys = new LinkedHashSet<>();
        Map<String, Ref> chosenByKey = new LinkedHashMap<>();
        for (String key : allKeys) {
            Ref b = baseByKey.get(key);
            Ref l = leftByKey.get(key);
            Ref r = rightByKey.get(key);
            if (sameRef(l, r)) {
                if (l != null) {
                    mergedKeys.add(key);
                    chosenByKey.put(key, l);
                }
            } else if (sameRef(l, b)) {
                if (r != null) {
                    mergedKeys.add(key);
                    chosenByKey.put(key, r);
                }
            } else if (sameRef(r, b)) {
                if (l != null) {
                    mergedKeys.add(key);
                    chosenByKey.put(key, l);
                }
            } else {
                // Truly divergent for this exact key: keep union optimistically.
                if (l != null) {
                    mergedKeys.add(key);
                    chosenByKey.put(key, l);
                }
                if (r != null) {
                    mergedKeys.add(key);
                    chosenByKey.putIfAbsent(key, r);
                }
            }
        }

        // Cross-key divergences for the same child name become explicit
        // conflicts; optimistic keeps the union, resolved honors the decision.
        Set<String> optimisticKeys = new LinkedHashSet<>(mergedKeys);
        Set<String> resolvedKeys = new LinkedHashSet<>(mergedKeys);

        Set<String> candidateNames = new LinkedHashSet<>();
        left.stream().map(Ref::name).forEach(candidateNames::add);
        right.stream().map(Ref::name).forEach(candidateNames::add);
        for (String childName : candidateNames.stream().sorted().toList()) {
            List<Ref> bRefs = base.stream().filter(rr -> rr.name().equals(childName)).toList();
            List<Ref> lRefs = left.stream().filter(rr -> rr.name().equals(childName)).toList();
            List<Ref> rRefs = right.stream().filter(rr -> rr.name().equals(childName)).toList();
            if (lRefs.isEmpty() || rRefs.isEmpty() || sameRefList(lRefs, rRefs)) {
                continue;
            }
            boolean addedIndependently = bRefs.isEmpty();
            boolean retargetedByBoth = !bRefs.isEmpty()
                    && !sameRefList(lRefs, bRefs) && !sameRefList(rRefs, bRefs);
            if (!addedIndependently && !retargetedByBoth) {
                continue;
            }
            Set<String> lKeys = lRefs.stream().map(Ref::key).collect(Collectors.toSet());
            Set<String> rKeys = rRefs.stream().map(Ref::key).collect(Collectors.toSet());
            Set<String> bKeys = bRefs.stream().map(Ref::key).collect(Collectors.toSet());
            Set<String> conflictKeys = new LinkedHashSet<>();
            conflictKeys.addAll(lKeys);
            conflictKeys.addAll(rKeys);
            conflictKeys.addAll(bKeys);

            String conflictId = "edge:" + owner + "->" + childName;
            conflicts.add(new Conflict(conflictId, Conflict.Type.EDGE_DIVERGE,
                    (owner.equals("root") ? "根依赖" : ("节点 " + owner))
                            + " 对子节点 " + childName + " 的引用在两侧分叉",
                    "left 引用 " + describeRefs(lRefs) + "；right 引用 "
                            + describeRefs(rRefs)
                            + (bRefs.isEmpty() ? "" : "；base 为 " + describeRefs(bRefs)),
                    bRefs, lRefs, rRefs));
            Decision decision = decisions.get(conflictId);
            if (decision != null) {
                Set<String> targetKeys = switch (decision.choice()) {
                    case Decision.LEFT -> lKeys;
                    case Decision.RIGHT -> rKeys;
                    case Decision.BOTH -> {
                        Set<String> both = new LinkedHashSet<>(lKeys);
                        both.addAll(rKeys);
                        yield both;
                    }
                    case Decision.BASE -> bKeys;
                    default -> {
                        resolutionErrors.add(conflictId + ": 未知选项 " + decision.choice());
                        yield conflictKeys;
                    }
                };
                resolvedKeys.removeAll(conflictKeys);
                resolvedKeys.addAll(targetKeys);
                for (Ref ref : lRefs) {
                    chosenByKey.put(ref.key(), ref);
                }
                for (Ref ref : rRefs) {
                    chosenByKey.put(ref.key(), ref);
                }
            }
        }

        List<Ref> optimistic = materialize(optimisticKeys, chosenByKey,
                leftByKey, rightByKey, baseByKey);
        List<Ref> resolved = materialize(resolvedKeys, chosenByKey,
                leftByKey, rightByKey, baseByKey);
        return new EdgeMerge(optimistic, resolved);
    }

    private static List<Ref> materialize(Set<String> keys, Map<String, Ref> chosenByKey,
                                         Map<String, Ref> leftByKey,
                                         Map<String, Ref> rightByKey,
                                         Map<String, Ref> baseByKey) {
        List<Ref> refs = new ArrayList<>();
        for (String key : keys) {
            Ref ref = chosenByKey.get(key);
            if (ref == null) {
                ref = leftByKey.get(key);
            }
            if (ref == null) {
                ref = rightByKey.get(key);
            }
            if (ref == null) {
                ref = baseByKey.get(key);
            }
            if (ref != null) {
                refs.add(ref);
            }
        }
        return sortedRefs(refs);
    }

    private static LockDocument buildGraph(Map<String, List<Ref>> edges,
                                           Map<String, Node> mergedNodes,
                                           Set<String> deletedNodeIds) {
        List<Ref> roots = edges.getOrDefault("root", List.of());
        List<Node> nodes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>(mergedNodes.keySet());
        ids.addAll(deletedNodeIds);
        for (String id : sortedIds(ids)) {
            if (deletedNodeIds.contains(id)) {
                continue;
            }
            Node node = mergedNodes.get(id);
            if (node == null) {
                continue;
            }
            List<Ref> children = edges.getOrDefault(id, List.of());
            nodes.add(node.withChildren(new ArrayList<>(children)));
        }
        return new LockDocument(new ArrayList<>(roots), nodes);
    }

    private static Map<String, Ref> indexByKey(List<Ref> refs) {
        Map<String, Ref> map = new LinkedHashMap<>();
        for (Ref ref : refs) {
            map.put(ref.key(), ref);
        }
        return map;
    }

    private static Map<String, String> childVersionMap(List<Ref> refs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Ref ref : refs) {
            map.put(ref.name(), ref.version());
        }
        return map;
    }

    private static boolean sameRefOrNull(Ref a, Ref b) {
        if (a == null || b == null) {
            return a == b;
        }
        return sameRef(a, b);
    }

    private static boolean sameRefList(List<Ref> a, List<Ref> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Ref> aIndex = indexByKey(a);
        for (Ref ref : b) {
            if (!sameRef(aIndex.get(ref.key()), ref)) {
                return false;
            }
        }
        return true;
    }

    private static String describeRefs(List<Ref> refs) {
        if (refs.isEmpty()) {
            return "(无)";
        }
        return refs.stream()
                .map(r -> r.name() + "@" + r.version()
                        + (r.condition() == null ? "" : " [" + r.condition() + "]"))
                .collect(Collectors.joining(", "));
    }

    private static List<String> sortedIds(Set<String> ids) {
        List<String> list = new ArrayList<>(ids);
        list.sort(Comparator.comparing((String id) -> id.substring(0, id.lastIndexOf('@')))
                .thenComparing(id -> id.substring(id.lastIndexOf('@') + 1)));
        return list;
    }

    /** Semantic node equality: platform compared via the normalized form. */
    public static boolean semanticEquals(Node a, Node b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.name(), b.name())
                && Objects.equals(a.version(), b.version())
                && Objects.equals(a.source(), b.source())
                && Objects.equals(a.integrity(), b.integrity())
                && Objects.equals(a.platform(), b.platform())
                && sameRefList(sortedRefs(a.children()), sortedRefs(b.children()));
    }

    private static String describeNodeDiff(Node b, Node l, Node r) {
        StringBuilder sb = new StringBuilder();
        if (b == null || !Objects.equals(b.source(), l.source())
                || !Objects.equals(b.source(), r.source())) {
            sb.append("来源：left=").append(l == null ? "-" : l.source())
                    .append("，right=").append(r == null ? "-" : r.source()).append('\n');
        }
        if (b == null || !Objects.equals(b.integrity(), l.integrity())
                || !Objects.equals(b.integrity(), r.integrity())) {
            sb.append("摘要：left=").append(l == null ? "-" : l.integrity())
                    .append("，right=").append(r == null ? "-" : r.integrity()).append('\n');
        }
        if (b == null || !Objects.equals(b.platform(), l.platform())
                || !Objects.equals(b.platform(), r.platform())) {
            sb.append("平台：left=").append(l == null || l.platform() == null ? "-" : l.platform())
                    .append("，right=").append(r == null || r.platform() == null ? "-" : r.platform())
                    .append('\n');
        }
        List<Ref> lc = l == null ? List.of() : sortedRefs(l.children());
        List<Ref> rc = r == null ? List.of() : sortedRefs(r.children());
        if (!sameRefList(lc, rc)) {
            sb.append("子引用：left={").append(describeRefs(lc))
                    .append("}，right={").append(describeRefs(rc)).append('}');
        }
        return sb.toString().strip();
    }

    record CrossDigest(String source, String version,
                       String leftDigest, String rightDigest) {
    }

    /**
     * Cross-side integrity check: gather the digest that each side asserts for
     * every (source, version) coordinate. When both sides assert a digest and
     * they differ, the coordinate must be adjudicated.
     */
    private static void detectCrossSideDigest(Inputs inputs, List<CrossDigest> issues) {
        record Claim(String digest, String nodeId) {
        }
        // sideIndex: 0=left 1=right
        List<Map<String, Claim>> sideClaims = new ArrayList<>(List.of(
                new LinkedHashMap<>(), new LinkedHashMap<>()));
        List<MergeEngine.SideInput> sides = List.of(inputs.left(), inputs.right());
        for (int sideIdx = 0; sideIdx < 2; sideIdx++) {
            Map<String, Claim> claims = sideClaims.get(sideIdx);
            for (Node node : sides.get(sideIdx).document().nodes()) {
                if (node.source().isBlank() || node.integrity().isBlank()) {
                    continue;
                }
                claims.putIfAbsent(node.source() + "|" + node.version(),
                        new Claim(node.integrity(), node.id()));
            }
        }
        Set<String> coords = new LinkedHashSet<>();
        coords.addAll(sideClaims.get(0).keySet());
        coords.addAll(sideClaims.get(1).keySet());
        for (String coord : coords) {
            Claim left = sideClaims.get(0).get(coord);
            Claim right = sideClaims.get(1).get(coord);
            if (left != null && right != null && !left.digest().equals(right.digest())) {
                int bar = coord.lastIndexOf('|');
                issues.add(new CrossDigest(coord.substring(0, bar), coord.substring(bar + 1),
                        left.digest(), right.digest()));
            }
        }
    }

    private static Node applyDigestChoices(Node node, Map<String, String> digestChoice) {
        if (node == null) {
            return null;
        }
        String chosen = digestChoice.get(node.source() + "|" + node.version());
        return chosen == null ? node : node.withIntegrity(chosen);
    }
}
