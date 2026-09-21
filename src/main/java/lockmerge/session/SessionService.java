package lockmerge.session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lockmerge.lock.LockParser;
import lockmerge.merge.ConcurrentRevisionException;
import lockmerge.merge.Conflict;
import lockmerge.merge.ConflictException;
import lockmerge.merge.Decision;
import lockmerge.merge.Evaluation;
import lockmerge.merge.MergeEngine;
import lockmerge.model.ParseResult;

/**
 * Application service around {@link MergeEngine}: owns the single resumable
 * session, persists it and enforces optimistic-lock revisions for concurrent
 * edits.
 */
public final class SessionService {

    private static final String SESSION_ID = "default";

    private final SessionStore store;
    private Session session;

    public SessionService(Path dataDirectory) {
        this.store = new SessionStore(dataDirectory, SESSION_ID);
        this.session = store.load();
        if (session == null) {
            session = new Session();
            session.id(SESSION_ID);
            store.save(session);
        }
    }

    public synchronized Session session() {
        return session;
    }

    /** Replace the raw inputs; old decisions stay recorded but never auto-apply. */
    public synchronized Evaluation updateInputs(String base, String left, String right,
                                                long expectedRevision) {
        checkRevision(expectedRevision);
        session.raws(base, left, right);
        session.revision(session.revision() + 1);
        Evaluation evaluation = reevaluate();
        store.save(session);
        return evaluation;
    }

    public synchronized Evaluation resolveConflict(String conflictId, String optionId,
                                                   long expectedRevision) {
        checkRevision(expectedRevision);
        Evaluation before = reevaluateWithoutSaving();
        Conflict target = before.conflicts().stream()
                .filter(c -> c.id().equals(conflictId))
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "no conflict with id '" + conflictId + "' (it may have expired)"));
        boolean valid = target.options().stream()
                .anyMatch(option -> option.id().equals(optionId));
        if (!valid) {
            throw new ConflictException(
                    "option '" + optionId + "' is not valid for conflict " + conflictId);
        }

        ParseResult baseResult = LockParser.parse("base", session.baseRaw());
        ParseResult leftResult = LockParser.parse("left", session.leftRaw());
        ParseResult rightResult = LockParser.parse("right", session.rightRaw());
        String summary = target.options().stream()
                .filter(option -> option.id().equals(optionId))
                .findFirst()
                .orElseThrow().label();
        Decision decision = new Decision(
                UUID.randomUUID().toString(),
                conflictId, optionId,
                combinedFingerprint(baseResult, leftResult, rightResult),
                baseResult.fingerprint(), leftResult.fingerprint(),
                rightResult.fingerprint(), summary, Instant.now());
        session.decisions().add(decision);
        session.revision(session.revision() + 1);
        Evaluation evaluation = reevaluate();
        store.save(session);
        return evaluation;
    }

    public synchronized Evaluation resetDecisions(long expectedRevision) {
        checkRevision(expectedRevision);
        session.decisions().clear();
        session.revision(session.revision() + 1);
        Evaluation evaluation = reevaluate();
        store.save(session);
        return evaluation;
    }

    public synchronized Evaluation evaluate() {
        return reevaluateWithoutSaving();
    }

    private Evaluation reevaluateWithoutSaving() {
        ParseResult base = LockParser.parse("base", session.baseRaw());
        ParseResult left = LockParser.parse("left", session.leftRaw());
        ParseResult right = LockParser.parse("right", session.rightRaw());
        String combined = combinedFingerprint(base, left, right);
        return MergeEngine.evaluate(new MergeEngine.Inputs(
                combined, base, left, right, List.copyOf(session.decisions())));
    }

    private Evaluation reevaluate() {
        Evaluation evaluation = reevaluateWithoutSaving();
        // Output versions are durable snapshots of clean merges only.
        session.recordOutput(evaluation.outputText(), evaluation.outputFingerprint());
        return evaluation;
    }

    private void checkRevision(long expected) {
        if (expected >= 0 && expected != session.revision()) {
            throw new ConcurrentRevisionException(
                    "session revision " + session.revision()
                            + " no longer matches expected " + expected
                            + "; reload the latest state before editing");
        }
    }

    public static String combinedFingerprint(ParseResult base, ParseResult left,
                                             ParseResult right) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, base.fingerprint());
            update(digest, left.fingerprint());
            update(digest, right.fingerprint());
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String part) {
        digest.update(part.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1e);
    }
}
