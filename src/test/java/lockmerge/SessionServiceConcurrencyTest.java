package lockmerge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lockmerge.merge.ConcurrentRevisionException;
import lockmerge.session.Session;
import lockmerge.session.SessionService;
import lockmerge.session.SessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionServiceConcurrencyTest {

    @TempDir
    Path tempDir;

    private String lockfile(String rootVersion) {
        return "lockfile v1\nroot app@" + rootVersion + "\n"
                + "package app@" + rootVersion + "\n"
                + "    source registry:acme/app\n"
                + "    integrity sha256:" + rootVersion.replace('.', '0') + "abcd\n";
    }

    @Test
    void optimisticLockRejectsStaleRevisionOnConcurrentEdits() throws Exception {
        SessionService service = new SessionService(tempDir);
        service.updateInputs(lockfile("1.0.0"), lockfile("1.0.0"),
                lockfile("1.0.0"), -1);
        long revision = service.session().revision();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> {
            start.await();
            service.updateInputs(lockfile("1.0.0"), lockfile("1.1.0"),
                    lockfile("1.0.0"), revision);
            return null;
        });
        Future<?> second = pool.submit(() -> {
            start.await();
            service.updateInputs(lockfile("1.0.0"), lockfile("1.0.0"),
                    lockfile("1.2.0"), revision);
            return null;
        });
        start.countDown();

        boolean oneFailed = false;
        for (Future<?> future : new Future<?>[]{first, second}) {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Throwable cause = e.getCause();
                assertTrue(cause instanceof ConcurrentRevisionException,
                        "one edit must fail the optimistic lock, got " + cause);
                oneFailed = true;
            }
        }
        pool.shutdown();
        assertTrue(oneFailed, "exactly one stale-revision edit must be rejected");
        assertEquals(revision + 1, service.session().revision());
    }

    @Test
    void unfinishedSessionCanBeResumedAfterRestart() throws IOException {
        SessionService service = new SessionService(tempDir);
        String base = "lockfile v1\nroot app@1.0.0\n"
                + "package app@1.0.0\n"
                + "    source registry:acme/app\n"
                + "    integrity sha256:1000abcd\n";
        String left = "lockfile v1\nroot app@1.0.0\n"
                + "package app@1.0.0\n"
                + "    source registry:acme/app\n"
                + "    integrity sha256:1000abce\n";
        String right = "lockfile v1\nroot app@1.0.0\n"
                + "package app@1.0.0\n"
                + "    source registry:acme/app\n"
                + "    integrity sha256:1000abcf\n";
        service.updateInputs(base, left, right, -1);
        // record a decision for the pending node conflict
        var evaluation = service.evaluate();
        String conflictId = evaluation.conflicts().get(0).id();
        service.resolveConflict(conflictId, "left", service.session().revision());
        long revisionBefore = service.session().revision();
        int decisionsBefore = service.session().decisions().size();
        String leftRaw = service.session().leftRaw();

        // restart: a new service pointed at the same directory
        SessionService restarted = new SessionService(tempDir);
        Session resumed = restarted.session();
        assertEquals(revisionBefore, resumed.revision());
        assertEquals(decisionsBefore, resumed.decisions().size());
        assertEquals(leftRaw, resumed.leftRaw());
        var after = restarted.evaluate();
        // the bound decision is replayed automatically on resume
        assertTrue(after.conflicts().stream()
                .filter(c -> c.id().equals(conflictId))
                .findFirst().orElseThrow().resolved());

        // and the store file actually exists with all durable content
        SessionStore store = new SessionStore(tempDir, "default");
        assertTrue(Files.exists(store.file()));
        String persisted = Files.readString(store.file());
        assertTrue(persisted.contains("decisions"));
        assertTrue(persisted.contains("sha256:1000abce"));
        assertTrue(persisted.contains("sha256:1000abcf"));
    }

    @Test
    void directStaleRevisionThrows() {
        SessionService service = new SessionService(tempDir);
        service.updateInputs(lockfile("1.0.0"), lockfile("1.0.0"),
                lockfile("1.0.0"), -1);
        long revision = service.session().revision();
        service.updateInputs(lockfile("1.0.0"), lockfile("1.1.0"),
                lockfile("1.0.0"), revision);
        assertThrows(ConcurrentRevisionException.class,
                () -> service.updateInputs(lockfile("1.0.0"), lockfile("1.2.0"),
                        lockfile("1.0.0"), revision));
    }
}
