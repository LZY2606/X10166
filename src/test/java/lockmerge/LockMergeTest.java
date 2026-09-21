package lockmerge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import lockmerge.lock.LockParser;
import lockmerge.lock.LockPrinter;
import lockmerge.merge.Conflict;
import lockmerge.merge.Decision;
import lockmerge.merge.Evaluation;
import lockmerge.merge.Issue;
import lockmerge.merge.MergeEngine;
import lockmerge.merge.PrunedNode;
import lockmerge.model.LockDocument;
import lockmerge.model.ParseResult;
import org.junit.jupiter.api.Test;

class LockMergeTest {

    private static String pkg(String name, String version, String source,
                              String integrity, String... requires) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(name).append('@').append(version).append('\n');
        sb.append("    source ").append(source).append('\n');
        if (integrity != null) {
            sb.append("    integrity ").append(integrity).append('\n');
        }
        if (requires.length > 0) {
            sb.append("    requires:\n");
            for (String req : requires) {
                sb.append("        ").append(req).append('\n');
            }
            sb.append("    end\n");
        }
        return sb.toString();
    }

    @Test
    void sameNameDifferentVersionsCoexistInGraphAndOutput() {
        String base = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");
        String left = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0", "lib@2.0.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111")
                + pkg("lib", "2.0.0", "registry:acme/lib", "sha256:bbbb2222");
        String right = base;

        Evaluation evaluation = TestFixtures.merge(base, left, right);

        assertFalse(evaluation.blocked(), () -> "should merge cleanly: "
                + evaluation.blockReasons());
        assertNotNull(evaluation.output());
        assertTrue(evaluation.output().nodes().containsKey(
                new lockmerge.model.NodeKey("lib", "1.0.0")));
        assertTrue(evaluation.output().nodes().containsKey(
                new lockmerge.model.NodeKey("lib", "2.0.0")));
        String printed = evaluation.outputText();
        assertTrue(printed.contains("package lib@1.0.0"));
        assertTrue(printed.contains("package lib@2.0.0"));
    }

    @Test
    void sameSourceCoordinateAndVersionWithConflictingDigestIsAnIssue() {
        String base = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0")
                + pkg("lib", "1.0.0", "registry:mirror/lib", "sha256:cccc1111");
        String left = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0", "alias@1.0.0")
                + pkg("lib", "1.0.0", "registry:mirror/lib", "sha256:cccc1111")
                + pkg("alias", "1.0.0", "registry:mirror/lib", "sha256:dddd9999");
        String right = base;

        Evaluation evaluation = TestFixtures.merge(base, left, right);

        Issue issue = evaluation.issues().stream()
                .filter(i -> i.kind().equals("INTEGRITY_MISMATCH"))
                .findFirst().orElse(null);
        assertNotNull(issue, "expected an integrity mismatch issue");
        assertTrue(issue.message().contains("registry:mirror/lib"));
        assertTrue(issue.message().contains("1.0.0"));
        assertTrue(evaluation.blocked());
        assertNull(evaluation.outputText());
    }

    @Test
    void deletingRootWhileOtherSideUpgradesTransitivesIsNotConflictAndPrunes() {
        String base = "lockfile v1\nroot app@1.0.0\nroot tool@3.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111")
                + pkg("tool", "3.0.0", "registry:acme/tool", "sha256:dddd1111",
                        "util@1.0.0")
                + pkg("util", "1.0.0", "registry:acme/util", "sha256:cccc1111");
        // left only upgrades the transitive node util (1.0.0 -> 1.2.0)
        String left = "lockfile v1\nroot app@1.0.0\nroot tool@3.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111")
                + pkg("tool", "3.0.0", "registry:acme/tool", "sha256:dddd1111",
                        "util@1.2.0")
                + pkg("util", "1.0.0", "registry:acme/util", "sha256:cccc1111")
                + pkg("util", "1.2.0", "registry:acme/util", "sha256:cccc2222");
        // right deletes root 'tool' entirely (orphan definitions remain textually)
        String right = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111")
                + pkg("tool", "3.0.0", "registry:acme/tool", "sha256:dddd1111",
                        "util@1.0.0")
                + pkg("util", "1.0.0", "registry:acme/util", "sha256:cccc1111");

        Evaluation evaluation = TestFixtures.merge(base, left, right);

        long rootConflicts = evaluation.conflicts().stream()
                .filter(c -> c.rootName() != null).count();
        assertEquals(0, rootConflicts,
                "deleting a root while the other side upgrades a transitive node is not a conflict");
        assertFalse(evaluation.blocked(), () -> evaluation.blockReasons().toString());

        List<String> prunedRefs = evaluation.prunedNodes().stream()
                .map(PrunedNode::nodeRef).toList();
        assertTrue(prunedRefs.contains("tool@3.0.0"));
        assertTrue(prunedRefs.contains("util@1.2.0"),
                "the upgraded transitive node becomes root-unreachable and must be pruned");
        PrunedNode upgraded = evaluation.prunedNodes().stream()
                .filter(p -> p.nodeRef().equals("util@1.2.0")).findFirst().orElseThrow();
        assertTrue(upgraded.reasons().stream()
                        .anyMatch(r -> r.contains("deleted root") && r.contains("tool")),
                "cleanup must explain the deleted-root chain: " + upgraded.reasons());

        assertNotNull(evaluation.output());
        assertNull(evaluation.output().root("tool"));
        assertFalse(evaluation.output().nodes().containsKey(
                new lockmerge.model.NodeKey("tool", "3.0.0")));
        assertFalse(evaluation.output().nodes().containsKey(
                new lockmerge.model.NodeKey("util", "1.2.0")));
        assertTrue(evaluation.output().nodes().containsKey(
                new lockmerge.model.NodeKey("app", "1.0.0")));
    }

    @Test
    void platformEquivalenceIsSemanticNotRawString() {
        // left changes only spelling/order of a platform expression; right
        // changes a child node. No NODE_CONTENT conflict may arise from the
        // semantically-equal condition.
        String base = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0 when os=macos | os=linux")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");
        String left = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0 when os=linux | os=macos")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");
        String right = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.1.0 when !!(os=macos | os=linux)")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111")
                + pkg("lib", "1.1.0", "registry:acme/lib", "sha256:bbbb2222");

        Evaluation evaluation = TestFixtures.merge(base, left, right);

        // app is unchanged semantically on both sides (right only changes edges
        // plus adds lib 1.1.0), so the merge stays conflict-free and clean
        assertFalse(evaluation.blocked(), () -> evaluation.blockReasons().toString());
        assertNotNull(evaluation.outputText());
        assertTrue(evaluation.outputText().contains(
                "when os=linux | os=macos"));
    }

    @Test
    void danglingChildReferenceAfterResolutionIsRejected() {
        String base = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");
        // both sides modify app differently: left points at lib@2.0.0 but never
        // defines it; right points at the existing lib@1.0.0 with a condition
        String left = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa9999",
                        "lib@2.0.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");
        String right = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0 when os=linux")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");

        Evaluation evaluation = TestFixtures.merge(base, left, right);

        Conflict appConflict = evaluation.conflicts().stream()
                .filter(c -> "node:app@1.0.0".equals(c.id())).findFirst()
                .orElseThrow();
        assertFalse(appConflict.resolved());
        Decision takeLeft = decisionFor(appConflict, "left", base, left, right);
        Evaluation resolved = TestFixtures.merge(base, left, right, List.of(takeLeft));

        Issue dangling = resolved.issues().stream()
                .filter(i -> i.kind().equals("DANGLING_EDGE"))
                .findFirst().orElse(null);
        assertNotNull(dangling, "left selection must revalidate and expose dangling edge");
        assertTrue(dangling.message().contains("lib@2.0.0"));
        assertTrue(resolved.blocked());
        assertNull(resolved.outputText());

        // choosing right instead must close the graph again
        Decision takeRight = decisionFor(appConflict, "right", base, left, right);
        Evaluation fixed = TestFixtures.merge(base, left, right, List.of(takeRight));
        assertFalse(fixed.blocked(), () -> fixed.blockReasons().toString());
        assertNotNull(fixed.outputText());
    }


    @Test
    void unreachableOrphanDefinitionsAreCleanedWithExplanations() {
        // An added node that nobody references and no root reaches is removed.
        String common = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111");
        String left = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111")
                + pkg("orphan", "9.9.9", "registry:acme/orphan", "sha256:ffff0000");
        String right = common;

        Evaluation evaluation = TestFixtures.merge(common, left, right);

        assertFalse(evaluation.blocked(), () -> evaluation.blockReasons().toString());
        PrunedNode orphan = evaluation.prunedNodes().stream()
                .filter(p -> p.nodeRef().equals("orphan@9.9.9")).findFirst()
                .orElseThrow();
        assertTrue(orphan.reasons().stream()
                .anyMatch(r -> r.contains("no surviving root")));
        assertFalse(evaluation.output().nodes().containsKey(
                new lockmerge.model.NodeKey("orphan", "9.9.9")));
    }

    @Test
    void oldDecisionIsNotAppliedAfterAnyInputFingerprintChanges() {
        String base = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.0.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111");
        String left = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.1.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111")
                + pkg("lib", "1.1.0", "registry:acme/lib", "sha256:bbbb2222");
        String right = "lockfile v1\nroot app@1.0.0\n"
                + pkg("app", "1.0.0", "registry:acme/app", "sha256:aaaa1111",
                        "lib@1.2.0")
                + pkg("lib", "1.0.0", "registry:acme/lib", "sha256:bbbb1111")
                + pkg("lib", "1.2.0", "registry:acme/lib", "sha256:bbbb3333");

        Evaluation first = TestFixtures.merge(base, left, right);
        Conflict appConflict = first.conflicts().stream()
                .filter(c -> "node:app@1.0.0".equals(c.id())).findFirst().orElseThrow();
        Decision decision = decisionFor(appConflict, "left", base, left, right);

        Evaluation applied = TestFixtures.merge(base, left, right, List.of(decision));
        assertEquals("left", applied.conflicts().stream()
                .filter(c -> c.id().equals("node:app@1.0.0")).findFirst().orElseThrow()
                .resolution());

        // now an input changes (right's digest changes) — fingerprints move
        String changedRight = right.replace("sha256:bbbb3333", "sha256:bbbb3344");
        Evaluation stale = TestFixtures.merge(base, left, changedRight, List.of(decision));
        Conflict stillPending = stale.conflicts().stream()
                .filter(c -> c.id().equals("node:app@1.0.0")).findFirst().orElseThrow();
        assertNull(stillPending.resolution(),
                "old decision must not auto-apply after an input change");
    }

    @Test
    void parsePrintParseIsEquivalentAndStableSorted() {
        String raw = "lockfile v1\nroot zeta@1.0.0\nroot alpha@2.1.0\n\n"
                + pkg("zeta", "1.0.0", "registry:acme/zeta", "sha256:abcd1111",
                        "mid@1.0.0 when os=linux", "mid@1.0.0 when os=macos")
                + pkg("mid", "1.0.0", "registry:acme/mid", null)
                + pkg("alpha", "2.1.0", "registry:acme/alpha", "sha256:aaaa9999");
        LockDocument doc1 = TestFixtures.documentOf(raw);
        String printed1 = LockPrinter.print(doc1);
        LockDocument doc2 = TestFixtures.documentOf(printed1);
        String printed2 = LockPrinter.print(doc2);

        assertEquals(doc1, doc2, "parse(print(parse(x))) must equal parse(x)");
        assertEquals(printed1, printed2, "printer must be stable/idempotent");
        // packages sorted by name then version
        int alphaPos = printed1.indexOf("package alpha@2.1.0");
        int midPos = printed1.indexOf("package mid@1.0.0");
        int zetaPos = printed1.indexOf("package zeta@1.0.0");
        assertTrue(alphaPos < midPos && midPos < zetaPos);
        // identical duplicate edge entries collapse in the model
        ParseResult reparsed = LockParser.parse("rt", printed1);
        assertFalse(reparsed.hasErrors());
    }

    private static Decision decisionFor(Conflict conflict, String optionId,
                                        String base, String left, String right) {
        ParseResult b = LockParser.parse("base", base);
        ParseResult l = LockParser.parse("left", left);
        ParseResult r = LockParser.parse("right", right);
        return new Decision(
                java.util.UUID.randomUUID().toString(),
                conflict.id(), optionId,
                lockmerge.session.SessionService.combinedFingerprint(b, l, r),
                b.fingerprint(), l.fingerprint(), r.fingerprint(),
                "test decision", java.time.Instant.now());
    }
}

