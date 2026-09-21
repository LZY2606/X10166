package lockmerge.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lockmerge.lock.LockPrinter;
import lockmerge.model.EdgeRef;
import lockmerge.model.LockDocument;
import lockmerge.model.NodeKey;
import lockmerge.model.PackageNode;
import lockmerge.model.ParseResult;
import lockmerge.model.RootDep;

/**
 * Semantic three-way merge over parsed lock graphs.
 *
 * <p>The merge is content-aware:
 * <ul>
 *   <li>same name, different versions coexist as independent nodes;</li>
 *   <li>integrity is checked by (source coordinate, version);</li>
 *   <li>deleting a root while the other side only upgrades transitives is not a
 *       conflict — unreachable nodes are pruned and explained;</li>
 *   <li>platform conditions are compared by canonical normalized expression;</li>
 *   <li>after a human choice, parent references and root reachability are
 *       revalidated (no dangling nodes are allowed);</li>
 *   <li>decisions only apply while all three input fingerprints are unchanged.</li>
 * </ul>
 */
public final class MergeEngine {

    private MergeEngine() {
    }

    public static final String SIDE_BASE = "base";
    public static final String SIDE_LEFT = "left";
    public static final String SIDE_RIGHT = "right";

    public record Inputs(String fingerprint, ParseResult base,
                         ParseResult left, ParseResult right, List<Decision> decisions) {
    }

    public static Evaluation evaluate(Inputs inputs) {
        ParseResult base = inputs.base();
        ParseResult left = inputs.left();
        ParseResult right = inputs.right();

        List<String> blockReasons = new ArrayList<>();
        collectParseBlocks(base, blockReasons);
        collectParseBlocks(left, blockReasons);
        collectParseBlocks(right, blockReasons);

        if (!blockReasons.isEmpty()) {
            return new Evaluation(base, left, right, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(),
                    null, null, null, true, List.copyOf(blockReasons));
        }

        LockDocument bDoc = base.document();
        LockDocument lDoc = left.document();
        LockDocument rDoc = right.document();

        Set<NodeKey> allKeys = new TreeSet<>();
        allKeys.addAll(bDoc.nodes().keySet());
        allKeys.addAll(lDoc.nodes().keySet());
        allKeys.addAll(rDoc.nodes().keySet());

        List<Conflict> conflicts = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        Map<NodeKey, NodeResolution> selected = new LinkedHashMap<>();

        for (NodeKey key : allKeys) {
            PackageNode b = bDoc.node(key);
            PackageNode l = lDoc.node(key);
            PackageNode r = rDoc.node(key);
            NodeResolution resolution = resolveNode(key, b, l, r, inputs, conflicts, changes);
            if (resolution != null) {
                selected.put(key, resolution);
            }
        }

        // ---- roots ----
        Map<String, RootState> rootStates = new TreeMap<>();
        gatherRoots(bDoc, SIDE_BASE, rootStates);
        gatherRoots(lDoc, SIDE_LEFT, rootStates);
        gatherRoots(rDoc, SIDE_RIGHT, rootStates);

        List<RootResolution> rootOrder = new ArrayList<>();
        Map<String, RootResolution> selectedRoots = new LinkedHashMap<>();
        for (Map.Entry<String, RootState> e : rootStates.entrySet()) {
            RootResolution rr = resolveRoot(e.getKey(), e.getValue(), inputs, conflicts, changes);
            if (rr != null) {
                selectedRoots.put(e.getKey(), rr);
                rootOrder.add(rr);
            }
        }
        rootOrder.sort((x, y) -> Integer.compare(x.order, y.order));

        // ---- candidate graph ----
        Map<NodeKey, GraphNode> graphNodes = new LinkedHashMap<>();
        for (NodeKey key : allKeys) {
            PackageNode b = bDoc.node(key);
            PackageNode l = lDoc.node(key);
            PackageNode r = rDoc.node(key);
            List<String> present = new ArrayList<>();
            List<String> origins = new ArrayList<>();
            addPresence(b, SIDE_BASE, present, origins);
            addPresence(l, SIDE_LEFT, present, origins);
            addPresence(r, SIDE_RIGHT, present, origins);
            NodeResolution chosen = selected.get(key);
            PackageNode chosenNode = chosen == null ? null : chosen.node();
            String chosenFromSide = chosen == null ? null : chosen.side();
            if (chosenNode != null) {
                origins = new ArrayList<>(origins);
                origins.add("merged:" + chosenFromSide);
            }
            graphNodes.put(key, new GraphNode(chosenNode, List.copyOf(present),
                    List.copyOf(origins), false, false, List.of()));
        }

        Set<NodeKey> reachable = new LinkedHashSet<>();
        Map<NodeKey, Set<String>> rootReachedBy = new LinkedHashMap<>();
        for (RootResolution rr : rootOrder) {
            Set<NodeKey> visited = new LinkedHashSet<>();
            walkReachable(rr.dep().pin(), selected, visited);
            reachable.addAll(visited);
            for (NodeKey k : visited) {
                rootReachedBy.computeIfAbsent(k, ignored -> new LinkedHashSet<>())
                        .add(rr.dep().name());
            }
        }

        // ---- prune + explain ----
        List<PrunedNode> pruned = new ArrayList<>();
        Set<NodeKey> kept = new TreeSet<>();
        for (NodeKey key : allKeys) {
            NodeResolution chosen = selected.get(key);
            if (chosen != null && reachable.contains(key)) {
                kept.add(key);
            }
        }
        for (NodeKey key : allKeys) {
            NodeResolution chosen = selected.get(key);
            if (chosen != null && reachable.contains(key)) {
                continue;
            }
            List<String> reasons = new ArrayList<>();
            boolean orphan = chosen != null;
            if (chosen != null) {
                reasons.addAll(unreachableReasons(key, bDoc, lDoc, rDoc, selectedRoots,
                        rootStates, inputs));
            } else {
                reasons.add("conflict at " + key.reference()
                        + " is pending, so the node is excluded from the output");
            }
            pruned.add(new PrunedNode(key.reference(), orphan, List.copyOf(reasons)));
        }

        // ---- edges of kept nodes (identical edges across sides are merged) ----
        record EdgeId(String from, String to, String platforms) {
        }
        Map<EdgeId, Set<String>> edgeOriginsMap = new LinkedHashMap<>();
        Map<EdgeId, Boolean> edgeTargetOk = new LinkedHashMap<>();
        for (NodeKey key : kept) {
            PackageNode node = selected.get(key).node();
            for (EdgeRef edge : node.requires()) {
                EdgeId edgeId = new EdgeId(key.reference(), edge.target().reference(),
                        edge.platforms());
                edgeOriginsMap.computeIfAbsent(edgeId, ignored -> new LinkedHashSet<>())
                        .addAll(edgeOrigins(edge, key, selected.get(key).side(),
                                bDoc, lDoc, rDoc));
                edgeTargetOk.put(edgeId, kept.contains(edge.target()));
            }
        }
        List<GraphEdge> finalEdges = new ArrayList<>();
        for (Map.Entry<EdgeId, Set<String>> e : edgeOriginsMap.entrySet()) {
            EdgeId id = e.getKey();
            finalEdges.add(new GraphEdge(id.from(), id.to(), id.platforms(),
                    edgeTargetOk.get(id), List.copyOf(e.getValue())));
        }
        Collections.sort(finalEdges);

        // ---- roots view ----
        List<GraphRoot> graphRoots = new ArrayList<>();
        for (RootResolution rr : rootOrder) {
            List<String> present = new ArrayList<>();
            List<String> origins = new ArrayList<>();
            RootState state = rootStates.get(rr.dep().name());
            addRootPresence(state.base, SIDE_BASE, present, origins);
            addRootPresence(state.left, SIDE_LEFT, present, origins);
            addRootPresence(state.right, SIDE_RIGHT, present, origins);
            origins = new ArrayList<>(origins);
            origins.add("merged:" + rr.side());
            graphRoots.add(new GraphRoot(rr.dep(), List.copyOf(present),
                    List.copyOf(origins), kept.contains(rr.dep().pin())));
        }

        // ---- issues ----
        List<Issue> issues = new ArrayList<>();
        detectDanglingEdges(kept, selected, issues);
        detectDanglingRoots(rootOrder, kept, issues);
        detectIntegrityMismatches(kept, selected, issues);

        // ---- final graph node view ----
        List<GraphNode> graphNodeList = new ArrayList<>();
        for (NodeKey key : allKeys) {
            GraphNode raw = graphNodes.get(key);
            boolean isReachable = kept.contains(key);
            boolean isPruned = !isReachable;
            List<String> reasons = List.of();
            if (isPruned) {
                for (PrunedNode pn : pruned) {
                    if (pn.nodeRef().equals(key.reference())) {
                        reasons = pn.reasons();
                        break;
                    }
                }
            }
            graphNodeList.add(new GraphNode(raw.node(), raw.presentIn(), raw.origins(),
                    isReachable, isPruned, reasons));
        }

        boolean pendingConflict = conflicts.stream().anyMatch(c -> !c.resolved());
        List<String> reasons = new ArrayList<>(blockReasons);
        if (pendingConflict) {
            reasons.add("there are unresolved conflicts");
        }
        for (Issue issue : issues) {
            reasons.add(issue.kind() + ": " + issue.message());
        }

        LockDocument output = null;
        String outputText = null;
        String outputFingerprint = null;
        if (reasons.isEmpty()) {
            List<RootDep> outRoots = new ArrayList<>();
            for (RootResolution rr : rootOrder) {
                outRoots.add(rr.dep());
            }
            Map<NodeKey, PackageNode> outNodes = new LinkedHashMap<>();
            for (NodeKey key : kept) {
                outNodes.put(key, selected.get(key).node());
            }
            output = new LockDocument(1, outRoots, outNodes);
            outputText = LockPrinter.print(output);
            outputFingerprint = lockmerge.lock.LockParser.fingerprintOf(outputText);
        }

        return new Evaluation(base, left, right,
                List.copyOf(conflicts), List.copyOf(changes), List.copyOf(issues),
                List.copyOf(pruned), List.copyOf(graphNodeList),
                List.copyOf(finalEdges), List.copyOf(graphRoots),
                output, outputText, outputFingerprint, !reasons.isEmpty(),
                List.copyOf(reasons));
    }

    // ---- internals ----------------------------------------------------

    private record NodeResolution(PackageNode node, String side) {
    }

    private record RootState(RootDep base, RootDep left, RootDep right) {
    }

    private record RootResolution(RootDep dep, String side, int order) {
    }

    private static void collectParseBlocks(ParseResult result, List<String> reasons) {
        if (result == null) {
            reasons.add("missing input: null");
            return;
        }
        for (var d : result.errors()) {
            reasons.add(result.label() + " parse error (" + d.code() + " line "
                    + d.line() + "): " + d.message());
        }
    }

    private static void addPresence(PackageNode node, String side,
                                    List<String> present, List<String> origins) {
        if (node != null) {
            present.add(side);
            origins.add(side);
        }
    }

    private static void addRootPresence(RootDep dep, String side,
                                        List<String> present, List<String> origins) {
        if (dep != null) {
            present.add(side);
            origins.add(side);
        }
    }

    private static void gatherRoots(LockDocument doc, String side,
                                    Map<String, RootState> states) {
        int index = 0;
        for (RootDep dep : doc.roots()) {
            RootState existing = states.get(dep.name());
            RootState updated = switch (side) {
                case SIDE_BASE -> new RootState(dep,
                        existing == null ? null : existing.left(),
                        existing == null ? null : existing.right());
                case SIDE_LEFT -> new RootState(existing == null ? null : existing.base(),
                        dep, existing == null ? null : existing.right());
                default -> new RootState(existing == null ? null : existing.base(),
                        existing == null ? null : existing.left(), dep);
            };
            states.put(dep.name(), updated);
            index++;
        }
    }

    private static List<String> nodeDifferences(PackageNode a, PackageNode b,
                                                String labelA, String labelB) {
        List<String> details = new ArrayList<>();
        if (!a.source().equals(b.source())) {
            details.add(labelA + " source " + a.source() + " \u2192 "
                    + labelB + " source " + b.source());
        }
        if (!Objects.equals(a.integrity(), b.integrity())) {
            details.add(labelA + " integrity " + display(a.integrity()) + " \u2192 "
                    + labelB + " integrity " + display(b.integrity()));
        }
        if (!Objects.equals(a.platforms(), b.platforms())) {
            details.add(labelA + " platforms " + displayPlatform(a.platforms()) + " \u2192 "
                    + labelB + " platforms " + displayPlatform(b.platforms()));
        }
        if (!a.requires().equals(b.requires())) {
            details.add("child references differ ("
                    + edgeSummary(a) + " vs " + edgeSummary(b) + ")");
        }
        return details;
    }

    private static String display(String value) {
        return value == null ? "(none)" : value;
    }

    private static String displayPlatform(String value) {
        return value == null ? "true" : value;
    }

    private static String edgeSummary(PackageNode node) {
        if (node.requires().isEmpty()) {
            return "no children";
        }
        List<String> parts = new ArrayList<>();
        for (EdgeRef edge : node.requires()) {
            parts.add(edge.target().reference()
                    + (edge.platforms() == null ? "" : " [" + edge.platforms() + "]"));
        }
        return String.join(", ", parts);
    }

    private static NodeResolution resolveNode(NodeKey key, PackageNode b, PackageNode l,
                                              PackageNode r, Inputs inputs,
                                              List<Conflict> conflicts,
                                              List<Change> changes) {
        String conflictId = "node:" + key.reference();

        if (b != null) {
            boolean lChanged = l != null && !l.equals(b);
            boolean rChanged = r != null && !r.equals(b);
            boolean lDeleted = l == null;
            boolean rDeleted = r == null;

            if (lDeleted && rDeleted) {
                changes.add(new Change("NODE_DELETED", "both", key.reference(),
                        List.of("both sides deleted the node")));
                return null;
            }
            if (lDeleted || rDeleted) {
                String deletingSide = lDeleted ? SIDE_LEFT : SIDE_RIGHT;
                PackageNode surviving = lDeleted ? r : l;
                String survivingSide = lDeleted ? SIDE_RIGHT : SIDE_LEFT;
                if (surviving.equals(b)) {
                    changes.add(new Change("NODE_DELETE_UNCHANGED", deletingSide,
                            key.reference(),
                            List.of(deletingSide + " deleted " + key.reference()
                                    + "; other side left it unchanged")));
                    return null;
                }
                Conflict conflict = new Conflict(conflictId, "NODE_DELETE_MODIFY",
                        key.reference(), null,
                        "node " + key.reference() + " deleted on " + deletingSide
                                + " but modified on " + survivingSide,
                        nodeDifferences(b, surviving, "base", survivingSide),
                        List.of(
                                new ConflictOption(survivingSide,
                                        "Keep modified (" + survivingSide + ")",
                                        survivingSide, key.reference(), true),
                                new ConflictOption(ConflictOption.DELETE,
                                        "Delete (" + deletingSide + ")", deletingSide,
                                        key.reference(), false),
                                new ConflictOption(ConflictOption.BASE,
                                        "Keep base version", SIDE_BASE,
                                        key.reference(), false)),
                        null, null, null);
                return applyNodeConflict(conflict, inputs, conflicts, b, l, r,
                        deletingSide, survivingSide);
            }
            // present on all sides
            if (l.equals(r)) {
                if (!l.equals(b)) {
                    changes.add(new Change("NODE_UPGRADED", "both", key.reference(),
                            nodeDifferences(b, l, "base", "left/right")));
                }
                return new NodeResolution(l, SIDE_LEFT);
            }
            if (l.equals(b)) {
                changes.add(new Change("NODE_CHANGED", SIDE_RIGHT, key.reference(),
                        nodeDifferences(b, r, "base", "right")));
                return new NodeResolution(r, SIDE_RIGHT);
            }
            if (r.equals(b)) {
                changes.add(new Change("NODE_CHANGED", SIDE_LEFT, key.reference(),
                        nodeDifferences(b, l, "base", "left")));
                return new NodeResolution(l, SIDE_LEFT);
            }
            Conflict conflict = new Conflict(conflictId, "NODE_CONTENT",
                    key.reference(), null,
                    "node " + key.reference() + " modified differently on both sides",
                    mergeDetails(
                            nodeDifferences(b, l, "base", "left"),
                            nodeDifferences(b, r, "base", "right")),
                    List.of(
                            new ConflictOption(SIDE_LEFT, "Take left version",
                                    SIDE_LEFT, key.reference(), true),
                            new ConflictOption(SIDE_RIGHT, "Take right version",
                                    SIDE_RIGHT, key.reference(), false),
                            new ConflictOption(ConflictOption.BASE, "Keep base version",
                                    SIDE_BASE, key.reference(), false)),
                    null, null, null);
            return applyNodeConflict(conflict, inputs, conflicts, b, l, r, null, null);
        }

        // absent in base: add / independent addition
        if (l != null && r != null && l.equals(r)) {
            changes.add(new Change("NODE_ADDED", "both", key.reference(),
                    List.of("added identically on both sides")));
            return new NodeResolution(l, SIDE_LEFT);
        }
        if (l != null && r == null) {
            changes.add(new Change("NODE_ADDED", SIDE_LEFT, key.reference(),
                    List.of("added only on left")));
            return new NodeResolution(l, SIDE_LEFT);
        }
        if (r != null && l == null) {
            changes.add(new Change("NODE_ADDED", SIDE_RIGHT, key.reference(),
                    List.of("added only on right")));
            return new NodeResolution(r, SIDE_RIGHT);
        }
        // both added differently: conflicting same-key additions
        Conflict conflict = new Conflict(conflictId, "NODE_CONTENT",
                key.reference(), null,
                "node " + key.reference() + " added differently on both sides",
                mergeDetails(
                        List.of("left: " + edgeSummary(l)),
                        List.of("right: " + edgeSummary(r))),
                List.of(
                        new ConflictOption(SIDE_LEFT, "Take left addition",
                                SIDE_LEFT, key.reference(), true),
                        new ConflictOption(SIDE_RIGHT, "Take right addition",
                                SIDE_RIGHT, key.reference(), false)),
                null, null, null);
        return applyNodeConflict(conflict, inputs, conflicts, b, l, r, null, null);
    }

    private static List<String> mergeDetails(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private static NodeResolution applyNodeConflict(Conflict conflict, Inputs inputs,
                                                    List<Conflict> conflicts,
                                                    PackageNode b, PackageNode l,
                                                    PackageNode r,
                                                    String deletingSide,
                                                    String survivingSide) {
        Decision decision = findDecision(conflict.id(), inputs);
        Conflict effective = conflict;
        NodeResolution resolution = null;
        if (decision != null) {
            PackageNode chosen = nodeForOption(decision.optionId(), b, l, r);
            boolean valid = isValidOption(conflict, decision.optionId())
                    && (decision.optionId().equals(ConflictOption.DELETE) || chosen != null);
            if (valid) {
                effective = conflict.withResolution(decision.optionId(),
                        "decision", decision.id());
                if (decision.optionId().equals(ConflictOption.DELETE)) {
                    resolution = null;
                } else {
                    resolution = new NodeResolution(chosen, decision.optionId());
                }
            }
        }
        conflicts.add(effective);
        return resolution;
    }

    private static boolean isValidOption(Conflict conflict, String optionId) {
        for (ConflictOption option : conflict.options()) {
            if (option.id().equals(optionId)) {
                return true;
            }
        }
        return false;
    }

    private static PackageNode nodeForOption(String optionId,
                                             PackageNode b, PackageNode l, PackageNode r) {
        return switch (optionId) {
            case SIDE_LEFT -> l;
            case SIDE_RIGHT -> r;
            case ConflictOption.BASE -> b;
            default -> null;
        };
    }

    // ---- roots --------------------------------------------------------

    private static RootResolution resolveRoot(String name, RootState state, Inputs inputs,
                                              List<Conflict> conflicts,
                                              List<Change> changes) {
        String conflictId = "root:" + name;
        RootDep b = state.base();
        RootDep l = state.left();
        RootDep r = state.right();

        if (b != null) {
            boolean lDeleted = l == null;
            boolean rDeleted = r == null;
            if (lDeleted && rDeleted) {
                changes.add(new Change("ROOT_DELETED", "both", name,
                        List.of("root '" + name + "' removed on both sides")));
                return null;
            }
            if (lDeleted || rDeleted) {
                String deletingSide = lDeleted ? SIDE_LEFT : SIDE_RIGHT;
                RootDep surviving = lDeleted ? r : l;
                String survivingSide = lDeleted ? SIDE_RIGHT : SIDE_LEFT;
                if (surviving.pin().equals(b.pin())) {
                    // deleting root while other side changes only transitives:
                    // this is NOT a conflict; the deleted root wins and its
                    // subtree is pruned via reachability cleanup
                    changes.add(new Change("ROOT_DELETED_ONE_SIDE", deletingSide, name,
                            List.of("root '" + name + "' deleted on " + deletingSide
                                    + " and unchanged on " + survivingSide
                                    + "; subtree becomes unreachable and is pruned")));
                    return null;
                }
                Conflict conflict = new Conflict(conflictId, "ROOT_DELETE_MODIFY",
                        null, name,
                        "root '" + name + "' deleted on " + deletingSide
                                + " but repinned on " + survivingSide,
                        List.of("base pins " + b.reference(),
                                deletingSide + " deletes the root",
                                survivingSide + " pins " + surviving.reference()),
                        List.of(
                                new ConflictOption(survivingSide,
                                        "Keep root (" + survivingSide + ": "
                                                + surviving.reference() + ")",
                                        survivingSide, surviving.reference(), true),
                                new ConflictOption(ConflictOption.DELETE,
                                        "Delete root (" + deletingSide + ")",
                                        deletingSide, null, false),
                                new ConflictOption(ConflictOption.BASE,
                                        "Keep base pin (" + b.reference() + ")",
                                        SIDE_BASE, b.reference(), false)),
                        null, null, null);
                return applyRootConflict(conflict, inputs, conflicts,
                        b, l, r, changes, deletingSide, survivingSide);
            }
            // present all sides
            if (l.pin().equals(r.pin())) {
                if (!l.pin().equals(b.pin())) {
                    changes.add(new Change("ROOT_REPINNED", "both", name,
                            List.of("root '" + name + "' repinned to " + l.reference())));
                }
                return new RootResolution(l, SIDE_LEFT, rootOrderIndex(inputs, name, l));
            }
            if (l.pin().equals(b.pin())) {
                changes.add(new Change("ROOT_REPINNED", SIDE_RIGHT, name,
                        List.of("root '" + name + "' repinned on right to " + r.reference())));
                return new RootResolution(r, SIDE_RIGHT, rootOrderIndex(inputs, name, r));
            }
            if (r.pin().equals(b.pin())) {
                changes.add(new Change("ROOT_REPINNED", SIDE_LEFT, name,
                        List.of("root '" + name + "' repinned on left to " + l.reference())));
                return new RootResolution(l, SIDE_LEFT, rootOrderIndex(inputs, name, l));
            }
            Conflict conflict = new Conflict(conflictId, "ROOT_CHANGED", null, name,
                    "root '" + name + "' repinned to different versions on both sides",
                    List.of("base: " + b.reference(),
                            "left: " + l.reference(), "right: " + r.reference()),
                    List.of(
                            new ConflictOption(SIDE_LEFT,
                                    "Take left (" + l.reference() + ")",
                                    SIDE_LEFT, l.reference(), true),
                            new ConflictOption(SIDE_RIGHT,
                                    "Take right (" + r.reference() + ")",
                                    SIDE_RIGHT, r.reference(), false),
                            new ConflictOption(ConflictOption.BASE,
                                    "Keep base (" + b.reference() + ")",
                                    SIDE_BASE, b.reference(), false)),
                    null, null, null);
            return applyRootConflict(conflict, inputs, conflicts, b, l, r, changes, null, null);
        }

        // absent in base
        if (l != null && r != null && l.pin().equals(r.pin())) {
            changes.add(new Change("ROOT_ADDED", "both", name,
                    List.of("root '" + name + "' added identically on both sides")));
            return new RootResolution(l, SIDE_LEFT, rootOrderIndex(inputs, name, l));
        }
        if (l != null && r == null) {
            changes.add(new Change("ROOT_ADDED", SIDE_LEFT, name,
                    List.of("root '" + name + "' added only on left")));
            return new RootResolution(l, SIDE_LEFT, rootOrderIndex(inputs, name, l));
        }
        if (r != null && l == null) {
            changes.add(new Change("ROOT_ADDED", SIDE_RIGHT, name,
                    List.of("root '" + name + "' added only on right")));
            return new RootResolution(r, SIDE_RIGHT, rootOrderIndex(inputs, name, r));
        }
        Conflict conflict = new Conflict(conflictId, "ROOT_CHANGED", null, name,
                "root '" + name + "' added with different pins on both sides",
                List.of("left: " + l.reference(), "right: " + r.reference()),
                List.of(
                        new ConflictOption(SIDE_LEFT, "Take left (" + l.reference() + ")",
                                SIDE_LEFT, l.reference(), true),
                        new ConflictOption(SIDE_RIGHT, "Take right (" + r.reference() + ")",
                                SIDE_RIGHT, r.reference(), false)),
                null, null, null);
        return applyRootConflict(conflict, inputs, conflicts, b, l, r, changes, null, null);
    }

    private static int rootOrderIndex(Inputs inputs, String name, RootDep dep) {
        ParseResult left = inputs.left();
        ParseResult right = inputs.right();
        int li = indexOfRoot(left.document(), dep);
        int ri = indexOfRoot(right.document(), dep);
        if (li < 0) {
            return ri;
        }
        if (ri < 0) {
            return li;
        }
        return Math.min(li, ri);
    }

    private static int indexOfRoot(LockDocument doc, RootDep dep) {
        for (int i = 0; i < doc.roots().size(); i++) {
            if (doc.roots().get(i).equals(dep)) {
                return i;
            }
        }
        return -1;
    }

    private static RootResolution applyRootConflict(Conflict conflict, Inputs inputs,
                                                    List<Conflict> conflicts,
                                                    RootDep b, RootDep l, RootDep r,
                                                    List<Change> changes,
                                                    String deletingSide,
                                                    String survivingSide) {
        Decision decision = findDecision(conflict.id(), inputs);
        Conflict effective = conflict;
        RootResolution resolution = null;
        if (decision != null && isValidOption(conflict, decision.optionId())) {
            RootDep chosen = rootForOption(decision.optionId(), b, l, r);
            if (decision.optionId().equals(ConflictOption.DELETE)
                    || chosen != null) {
                effective = conflict.withResolution(decision.optionId(),
                        "decision", decision.id());
                if (chosen != null) {
                    resolution = new RootResolution(chosen, decision.optionId(),
                            rootOrderIndex(inputs, chosen.name(), chosen));
                }
            }
        }
        conflicts.add(effective);
        return resolution;
    }

    private static RootDep rootForOption(String optionId,
                                         RootDep b, RootDep l, RootDep r) {
        return switch (optionId) {
            case SIDE_LEFT -> l;
            case SIDE_RIGHT -> r;
            case ConflictOption.BASE -> b;
            default -> null;
        };
    }

    private static Decision findDecision(String conflictId, Inputs inputs) {
        Decision latest = null;
        for (Decision decision : inputs.decisions()) {
            if (decision.conflictId().equals(conflictId)
                    && decision.appliesTo(inputs.fingerprint(),
                            fpOrEmpty(inputs.base()),
                            fpOrEmpty(inputs.left()),
                            fpOrEmpty(inputs.right()))) {
                latest = decision;
            }
        }
        return latest;
    }

    private static String fpOrEmpty(ParseResult result) {
        return result == null ? "" : result.fingerprint();
    }

    // ---- reachability & pruning explanations --------------------------

    private static void walkReachable(NodeKey start, Map<NodeKey, NodeResolution> selected,
                                     Set<NodeKey> visited) {
        if (visited.contains(start) || !selected.containsKey(start)) {
            return;
        }
        visited.add(start);
        PackageNode node = selected.get(start).node();
        if (node == null) {
            return;
        }
        for (EdgeRef edge : node.requires()) {
            walkReachable(edge.target(), selected, visited);
        }
    }

    private static List<String> unreachableReasons(
            NodeKey key,
            LockDocument bDoc, LockDocument lDoc, LockDocument rDoc,
            Map<String, RootResolution> selectedRoots,
            Map<String, RootState> rootStates,
            Inputs inputs) {
        List<String> reasons = new ArrayList<>();

        // Was this node once reachable only through a root that has now been deleted?
        Set<String> deletedRoots = new TreeSet<>();
        for (Map.Entry<String, RootState> e : rootStates.entrySet()) {
            if (!selectedRoots.containsKey(e.getKey())) {
                deletedRoots.add(e.getKey());
            }
        }
        Set<String> viaDeletedRoots = new TreeSet<>();
        for (String rootName : deletedRoots) {
            RootState state = rootStates.get(rootName);
            for (SideDoc sideDoc : new SideDoc[]{
                    new SideDoc(SIDE_BASE, bDoc),
                    new SideDoc(SIDE_LEFT, lDoc),
                    new SideDoc(SIDE_RIGHT, rDoc)}) {
                RootDep root = sideDoc.root(state);
                if (root != null && reachesInDoc(sideDoc.doc(), root.pin(), key)) {
                    viaDeletedRoots.add(rootName + " (in " + sideDoc.side() + ")");
                }
            }
        }
        if (!viaDeletedRoots.isEmpty()) {
            reasons.add("only reachable through deleted root(s): "
                    + String.join(", ", viaDeletedRoots)
                    + " — not a conflict; removed by reachability cleanup");
        }

        // Was it an orphan definition left after a one-sided root delete?
        boolean definedOnlyBySide = existsOnlyOnOneSide(key, bDoc, lDoc, rDoc);
        if (definedOnlyBySide) {
            String side = definedOnSide(key, bDoc, lDoc, rDoc);
            reasons.add("node definition retained on " + side
                    + " but no surviving root or reachable parent references it");
        }

        if (reasons.isEmpty()) {
            Set<String> incomingKept = incomingReachableParents(key, selectedRoots,
                    bDoc, lDoc, rDoc, inputs);
            if (incomingKept.isEmpty()) {
                reasons.add("no root or reachable parent node references "
                        + key.reference());
            } else {
                reasons.add("referenced only by pruned nodes: "
                        + String.join(", ", incomingKept));
            }
        }
        return reasons;
    }

    private record SideDoc(String side, LockDocument doc) {
        RootDep root(RootState state) {
            return switch (side) {
                case SIDE_BASE -> state.base();
                case SIDE_LEFT -> state.left();
                default -> state.right();
            };
        }
    }

    private static boolean reachesInDoc(LockDocument doc, NodeKey start, NodeKey target) {
        Set<NodeKey> visited = new LinkedHashSet<>();
        walkDoc(doc, start, visited);
        return visited.contains(target);
    }

    private static void walkDoc(LockDocument doc, NodeKey key, Set<NodeKey> visited) {
        if (visited.contains(key) || !doc.nodes().containsKey(key)) {
            return;
        }
        visited.add(key);
        PackageNode node = doc.node(key);
        for (EdgeRef edge : node.requires()) {
            walkDoc(doc, edge.target(), visited);
        }
    }

    private static boolean existsOnlyOnOneSide(NodeKey key, LockDocument bDoc,
                                               LockDocument lDoc, LockDocument rDoc) {
        boolean inB = bDoc.nodes().containsKey(key);
        boolean inL = lDoc.nodes().containsKey(key);
        boolean inR = rDoc.nodes().containsKey(key);
        int count = (inB ? 1 : 0) + (inL ? 1 : 0) + (inR ? 1 : 0);
        return count == 1;
    }

    private static String definedOnSide(NodeKey key, LockDocument bDoc,
                                        LockDocument lDoc, LockDocument rDoc) {
        if (bDoc.nodes().containsKey(key)) {
            return SIDE_BASE;
        }
        if (lDoc.nodes().containsKey(key)) {
            return SIDE_LEFT;
        }
        return SIDE_RIGHT;
    }

    private static Set<String> incomingReachableParents(
            NodeKey target, Map<String, RootResolution> selectedRoots,
            LockDocument bDoc, LockDocument lDoc, LockDocument rDoc, Inputs inputs) {
        Set<String> parents = new TreeSet<>();
        for (LockDocument doc : List.of(bDoc, lDoc, rDoc)) {
            for (PackageNode node : doc.nodes().values()) {
                for (EdgeRef edge : node.requires()) {
                    if (edge.target().equals(target)) {
                        parents.add(node.key().reference());
                    }
                }
            }
        }
        return parents;
    }

    // ---- validation ---------------------------------------------------

    private static void detectDanglingEdges(Set<NodeKey> kept,
                                            Map<NodeKey, NodeResolution> selected,
                                            List<Issue> issues) {
        for (NodeKey key : kept) {
            PackageNode node = selected.get(key).node();
            for (EdgeRef edge : node.requires()) {
                if (!kept.contains(edge.target())) {
                    issues.add(new Issue("DANGLING_EDGE",
                            node.key().reference() + " references missing child "
                                    + edge.target().reference()
                                    + (edge.platforms() == null ? ""
                                            : " when " + edge.platforms())
                                    + " — revalidate parent references after resolution"));
                }
            }
        }
    }

    private static void detectDanglingRoots(List<RootResolution> roots,
                                            Set<NodeKey> kept, List<Issue> issues) {
        for (RootResolution rr : roots) {
            if (!kept.contains(rr.dep().pin())) {
                issues.add(new Issue("DANGLING_ROOT",
                        "root '" + rr.dep().name() + "' pins missing node "
                                + rr.dep().reference()
                                + " — root reachability cannot be satisfied"));
            }
        }
    }

    /**
     * Same source coordinate and version must agree on the integrity digest.
     * Same name with different versions are separate coordinates and coexist.
     */
    private static void detectIntegrityMismatches(
            Set<NodeKey> kept, Map<NodeKey, NodeResolution> selected,
            List<Issue> issues) {
        record Coordinate(String source, String version) {
        }
        Map<Coordinate, Map<String, List<NodeKey>>> byCoordinate = new LinkedHashMap<>();
        for (NodeKey key : kept) {
            PackageNode node = selected.get(key).node();
            Coordinate coordinate = new Coordinate(node.source(), key.version());
            String digest = node.integrity() == null ? "" : node.integrity();
            byCoordinate.computeIfAbsent(coordinate, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(digest, ignored -> new ArrayList<>()).add(key);
        }
        for (Map.Entry<Coordinate, Map<String, List<NodeKey>>> e
                : byCoordinate.entrySet()) {
            Map<String, List<NodeKey>> digests = e.getValue();
            if (digests.size() > 1) {
                List<String> groups = new ArrayList<>();
                for (Map.Entry<String, List<NodeKey>> g : digests.entrySet()) {
                    List<String> refs = g.getValue().stream()
                            .map(NodeKey::reference).sorted().toList();
                    String digestLabel = g.getKey().isEmpty()
                            ? "(no integrity)" : g.getKey();
                    groups.add(digestLabel + " -> " + String.join(", ", refs));
                }
                issues.add(new Issue("INTEGRITY_MISMATCH",
                        "source coordinate '" + e.getKey().source()
                                + "' version " + e.getKey().version()
                                + " has conflicting integrity digests: "
                                + String.join("; ", groups)));
            }
        }
    }

    private static List<String> edgeOrigins(EdgeRef edge, NodeKey parentKey,
                                            String chosenSide,
                                            LockDocument bDoc, LockDocument lDoc,
                                            LockDocument rDoc) {
        List<String> origins = new ArrayList<>();
        if (hasEdge(bDoc, parentKey, edge)) {
            origins.add(SIDE_BASE);
        }
        if (hasEdge(lDoc, parentKey, edge)) {
            origins.add(SIDE_LEFT);
        }
        if (hasEdge(rDoc, parentKey, edge)) {
            origins.add(SIDE_RIGHT);
        }
        if (origins.isEmpty() && chosenSide != null) {
            origins.add(chosenSide);
        }
        return origins;
    }

    private static boolean hasEdge(LockDocument doc, NodeKey parentKey, EdgeRef wanted) {
        PackageNode node = doc.node(parentKey);
        if (node == null) {
            return false;
        }
        for (EdgeRef edge : node.requires()) {
            if (edge.target().equals(wanted.target())
                    && Objects.equals(edge.platforms(), wanted.platforms())) {
                return true;
            }
        }
        return false;
    }
}
