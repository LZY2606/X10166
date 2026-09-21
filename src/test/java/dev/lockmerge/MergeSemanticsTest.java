package dev.lockmerge;

import dev.lockmerge.merge.Conflict;
import dev.lockmerge.merge.Decision;
import dev.lockmerge.merge.MergeEngine;
import dev.lockmerge.model.LockDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeSemanticsTest {

    private static final String A =
            "sha256:000000000000000000000000000000000000000000000000000000000000aaaa";
    private static final String B =
            "sha256:000000000000000000000000000000000000000000000000000000000000bbbb";
    private static final String C =
            "sha256:000000000000000000000000000000000000000000000000000000000000cccc";

    static String node(String name, String version, String digest, String... children) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(name).append('@').append(version).append('\n');
        sb.append("  source registry:https://repo.example.com/")
                .append(name).append('-').append(version).append(".tgz\n");
        sb.append("  integrity ").append(digest).append('\n');
        for (String child : children) {
            sb.append("  child ").append(child).append('\n');
        }
        return sb.toString();
    }

    static String roots(String... roots) {
        StringBuilder sb = new StringBuilder("lockfile v1\n");
        for (String root : roots) {
            sb.append("root ").append(root).append('\n');
        }
        return sb.toString();
    }

    static String doc(String roots, String... nodes) {
        return roots + "\n" + String.join("\n", nodes);
    }

    private MergeEngine.Result merge(String base, String left, String right,
                                     Decision... decisions) {
        MergeEngine.Inputs inputs = Fixtures.inputs(base, left, right);
        return MergeEngine.compute(inputs, List.of(decisions));
    }

    @Test
    void sameNameDifferentVersionsCoexist() {
        String base = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.0.0", "libx@1.1.0"),
                node("libx", "1.0.0", B),
                node("libx", "1.1.0", C));
        MergeEngine.Result result = merge(base, base, base);
        assertTrue(result.canPublish());
        LockDocument graph = result.resolvedGraph;
        assertEquals(3, graph.nodes().size());
        assertTrue(graph.nodeIndex().containsKey("libx@1.0.0"));
        assertTrue(graph.nodeIndex().containsKey("libx@1.1.0"));
    }

    @Test
    void conflictingDigestForSameSourceCoordinateAndVersionIsAConflict() {
        String left = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@2.0.0"),
                node("libx", "2.0.0", B));
        // Same source coordinate (registry URL is version-pinned) but different digest.
        String rightLib = node("libx", "2.0.0", C);
        String right = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@2.0.0"),
                rightLib);
        String base = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.9.0"),
                node("libx", "1.9.0", B));

        MergeEngine.Result unresolved = merge(base, left, right);
        boolean integrityConflict = unresolved.conflicts.stream()
                .anyMatch(c -> c.type() == Conflict.Type.INTEGRITY);
        assertTrue(integrityConflict, "expected an integrity conflict");
        assertFalse(unresolved.canPublish());

        Conflict conflict = unresolved.conflicts.stream()
                .filter(c -> c.type() == Conflict.Type.INTEGRITY).findFirst().orElseThrow();
        Decision pickRight = new Decision(conflict.id(), Decision.RIGHT,
                Fixtures.fp(base), Fixtures.fp(left), Fixtures.fp(right));
        MergeEngine.Result resolved = merge(base, left, right, pickRight);
        assertTrue(resolved.canPublish());
        assertEquals(C, resolved.resolvedGraph.nodeIndex().get("libx@2.0.0").integrity());
    }

    @Test
    void deletingRootWhileOtherSideOnlyUpgradesTransitiveNodeCleansUpWithoutConflict() {
        String base = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.0.0"),
                node("libx", "1.0.0", B));
        // left removes the root dependency entirely.
        String left = roots();
        // right upgrades the transitive node only, still rooted at app.
        String right = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.1.0"),
                node("libx", "1.1.0", C));

        MergeEngine.Result result = merge(base, left, right);
        boolean nodeDeleteConflict = result.conflicts.stream()
                .anyMatch(c -> c.type() == Conflict.Type.NODE_DELETE);
        assertFalse(nodeDeleteConflict,
                "delete-root vs transitive-only-upgrade must not be a node conflict");
        assertTrue(result.canPublish());
        assertTrue(result.resolvedGraph.nodes().isEmpty(),
                "no root remains so every node must be cleaned");
        assertTrue(result.removed.stream().anyMatch(r -> r.id().equals("app@1.0.0")));
        assertTrue(result.removed.stream().anyMatch(r -> r.id().equals("libx@1.1.0")));
    }

    @Test
    void platformConditionsComparedByNormalizedExpression() {
        // left and right phrase the same condition differently (raw strings differ).
        String left = doc(roots("app@1.0.0 [os=linux | os=darwin]"),
                node("app", "1.0.0", A));
        String right = doc(roots("app@1.0.0 [os=darwin | os=linux]"),
                node("app", "1.0.0", A));
        String base = doc(roots("app@1.0.0 [os=windows]"),
                node("app", "1.0.0", A));

        MergeEngine.Result bothChangeSame = merge(base, left, right);
        // Both sides normalize to the same minterm set: no edge divergence.
        assertTrue(bothChangeSame.conflicts.isEmpty(),
                "equivalent normalized conditions must not conflict");
        assertTrue(bothChangeSame.canPublish());
        assertEquals("os=darwin|os=linux",
                bothChangeSame.resolvedGraph.roots().get(0).condition());
    }

    @Test
    void danglingReferenceInInputIsDiagnosed() {
        String text = "lockfile v1\nroot app@1.0.0\n\n"
                + node("app", "1.0.0", A, "ghost@9.9.9");
        var parsed = Fixtures.parse(text);
        var report = dev.lockmerge.merge.Validator.validate(
                dev.lockmerge.lock.LockParser.parse(text).document);
        assertEquals(1, report.danglingRefs.size());
        assertEquals("ghost", report.danglingRefs.get(0).name());
        assertFalse(parsed.hasErrors());
    }

    @Test
    void manualDeleteChoiceRevalidatesParentsAndReachabilityWithoutDanglingNodes() {
        // base has two roots. left drops root extra and its subtree; right keeps
        // the SAME extra@1.0.0 id but modifies its platform condition -> a real
        // delete/modify NODE_DELETE conflict.
        String base = doc(roots("app@1.0.0", "extra@1.0.0"),
                node("app", "1.0.0", A),
                node("extra", "1.0.0", B, "helper@1.0.0"),
                node("helper", "1.0.0", C));
        String left = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A));
        String right = doc(roots("app@1.0.0", "extra@1.0.0"),
                node("app", "1.0.0", A),
                "package extra@1.0.0\n"
                        + "  source registry:https://repo.example.com/extra-1.0.0.tgz\n"
                        + "  integrity " + B + "\n"
                        + "  platform !os=windows\n"
                        + "  child helper@1.0.0\n",
                node("helper", "1.0.0", C));

        MergeEngine.Result unresolved = merge(base, left, right);
        Conflict deleteConflict = unresolved.conflicts.stream()
                .filter(c -> c.type() == Conflict.Type.NODE_DELETE
                        && c.id().equals("node:extra@1.0.0"))
                .findFirst().orElseThrow();

        // User chooses LEFT which deleted extra; the whole subtree must be
        // re-checked for parent refs and reachability, leaving no dangling.
        Decision deleteExtra = new Decision(deleteConflict.id(), Decision.LEFT,
                Fixtures.fp(base), Fixtures.fp(left), Fixtures.fp(right));
        MergeEngine.Result resolved = merge(base, left, right, deleteExtra);
        assertTrue(resolved.canPublish());
        assertFalse(resolved.resolvedGraph.nodeIndex().containsKey("extra@1.0.0"));
        assertTrue(resolved.removed.stream().anyMatch(r -> r.id().equals("helper@1.0.0")));
        assertTrue(resolved.dangling.isEmpty());
    }

    @Test
    void decisionBoundToOldFingerprintsIsStaleAndNotApplied() {
        String base = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.0.0"),
                node("libx", "1.0.0", B));
        String left = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.1.0"),
                node("libx", "1.1.0", C));
        String right = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.2.0"),
                node("libx", "1.2.0", A));

        MergeEngine.Result first = merge(base, left, right);
        Conflict conflict = first.conflicts.stream()
                .filter(c -> c.type() == Conflict.Type.EDGE_DIVERGE)
                .findFirst().orElseThrow();

        // Bind a decision to a tampered fingerprint triple.
        Decision stale = new Decision(conflict.id(), Decision.LEFT,
                "sha256:deadbeef", Fixtures.fp(left), Fixtures.fp(right));
        MergeEngine.Result rerun = merge(base, left, right, stale);
        assertTrue(rerun.staleDecisionIds.contains(conflict.id()));
        assertTrue(rerun.appliedDecisions.isEmpty(),
                "a stale decision must never be applied automatically");
        assertTrue(rerun.conflicts.stream().anyMatch(c -> c.id().equals(conflict.id())));
    }

    @Test
    void edgeDivergenceIsReportedAndResolvable() {
        String base = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A));
        String left = doc(roots("app@1.0.0", "libx@1.0.0"),
                node("app", "1.0.0", A),
                node("libx", "1.0.0", B));
        String right = doc(roots("app@1.0.0", "libx@2.0.0"),
                node("app", "1.0.0", A),
                node("libx", "2.0.0", C));

        MergeEngine.Result result = merge(base, left, right);
        Conflict conflict = result.conflicts.stream()
                .filter(c -> c.type() == Conflict.Type.EDGE_DIVERGE).findFirst().orElseThrow();
        Decision pickLeft = new Decision(conflict.id(), Decision.LEFT,
                Fixtures.fp(base), Fixtures.fp(left), Fixtures.fp(right));
        MergeEngine.Result resolved = merge(base, left, right, pickLeft);
        assertTrue(resolved.canPublish());
        assertTrue(resolved.resolvedGraph.nodeIndex().containsKey("libx@1.0.0"));
        assertFalse(resolved.resolvedGraph.nodeIndex().containsKey("libx@2.0.0"));
    }
}
