package dev.lockmerge;

import dev.lockmerge.merge.Decision;
import dev.lockmerge.merge.MergeService;
import dev.lockmerge.persist.Session;
import dev.lockmerge.persist.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeServiceTest {

    private static final String A =
            "sha256:000000000000000000000000000000000000000000000000000000000000aaaa";
    private static final String B =
            "sha256:000000000000000000000000000000000000000000000000000000000000bbbb";
    private static final String C =
            "sha256:000000000000000000000000000000000000000000000000000000000000cccc";
    private static final String D =
            "sha256:000000000000000000000000000000000000000000000000000000000000dddd";

    @TempDir
    Path tempDir;
    private SessionStore store;
    private MergeService service;

    @BeforeEach
    void setUp() {
        store = new SessionStore(tempDir);
        service = new MergeService(store);
    }

    private static String node(String name, String version, String digest, String... children) {
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

    private static String roots(String... roots) {
        StringBuilder sb = new StringBuilder("lockfile v1\n");
        for (String root : roots) {
            sb.append("root ").append(root).append('\n');
        }
        return sb.toString();
    }

    private static String doc(String roots, String... nodes) {
        return roots + "\n" + String.join("\n", nodes);
    }

    @Test
    void unfinishedSessionSurvivesRestart() {
        Session session = service.createSession();
        String base = doc(roots("app@1.0.0"), node("app", "1.0.0", A));
        service.submitInputs(session.id, session.revision, base, base, base);

        SessionStore reopened = new SessionStore(tempDir);
        MergeService restarted = new MergeService(reopened);
        Session loaded = restarted.require(session.id);
        assertEquals(base, loaded.baseText);
        assertTrue(restarted.evaluate(loaded).canPublish());
    }

    @Test
    void oldDecisionIsInvalidatedWhenAnyInputChanges() {
        String base = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.0.0"),
                node("libx", "1.0.0", B));
        String left = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.1.0"),
                node("libx", "1.1.0", C));
        String right = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.2.0"),
                node("libx", "1.2.0", D));

        Session session = service.createSession();
        service.submitInputs(session.id, session.revision, base, left, right);
        session = service.require(session.id);

        var result = service.evaluate(session);
        String edgeConflict = result.conflicts.stream()
                .filter(c -> c.type() == dev.lockmerge.merge.Conflict.Type.EDGE_DIVERGE)
                .findFirst().orElseThrow().id();
        Decision decision = new Decision(edgeConflict, Decision.LEFT,
                Fixtures.fp(base), Fixtures.fp(left), Fixtures.fp(right));
        session = service.addDecision(session.id, session.revision, decision);
        assertEquals(1, service.evaluate(session).appliedDecisions.size());

        // Change only right input; fingerprints change, old decision must not apply.
        String rightChanged = right.replace("1.2.0", "1.2.0")
                + "\n# changed\n";
        session = service.submitInputs(session.id, session.revision, base, left, rightChanged);
        var rerun = service.evaluate(session);
        assertTrue(rerun.staleDecisionIds.contains(edgeConflict));
        assertEquals(0, rerun.appliedDecisions.size());
    }

    @Test
    void concurrentEditsWithStaleRevisionAreRejected() {
        Session session = service.createSession();
        long revision = session.revision;
        String base = doc(roots("app@1.0.0"), node("app", "1.0.0", A));

        // First edit succeeds and bumps the revision.
        Session afterFirst = service.submitInputs(session.id, revision, base, base, base);
        assertEquals(revision + 1, afterFirst.revision);

        // Second edit using the original revision is a concurrent-edit conflict.
        assertThrows(MergeService.ConflictException.class,
                () -> service.submitInputs(session.id, revision, base, base, base));
    }

    @Test
    void trulyConcurrentRequestsSerializeAndExactlyOneWins() throws Exception {
        Session session = service.createSession();
        long revision = session.revision;
        String base = doc(roots("app@1.0.0"), node("app", "1.0.0", A));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        Runnable task = () -> {
            try {
                start.await();
                service.submitInputs(session.id, revision, base, base, base);
            } catch (MergeService.ConflictException e) {
                failures.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        start.countDown();
        f1.get();
        f2.get();
        pool.shutdown();
        assertEquals(1, failures.get(), "exactly one concurrent edit must lose");
        assertEquals(revision + 1, service.require(session.id).revision);
    }

    @Test
    void publishCreatesNumberedStableOutput() {
        String text = doc(roots("app@1.0.0"),
                node("app", "1.0.0", A, "libx@1.0.0"),
                node("libx", "1.0.0", B));
        Session session = service.createSession();
        session = service.submitInputs(session.id, session.revision, text, text, text);
        session = service.publish(session.id, session.revision);
        assertEquals(1, session.versions.size());
        assertNotNull(session.versions.get(0).text);
        String published = session.versions.get(0).text;
        assertTrue(published.startsWith("lockfile v1\n"));

        // Re-publish bumps version numbers.
        session = service.publish(session.id, session.revision);
        assertEquals(2, session.versions.size());
        assertEquals(session.versions.get(0).text, session.versions.get(1).text);
    }
}
