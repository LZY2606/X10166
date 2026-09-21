package lockmerge;

import java.util.List;
import lockmerge.lock.LockParser;
import lockmerge.lock.LockPrinter;
import lockmerge.merge.Decision;
import lockmerge.merge.Evaluation;
import lockmerge.merge.MergeEngine;
import lockmerge.model.LockDocument;
import lockmerge.model.ParseResult;

/** Shared helpers for lockfile merge tests. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static ParseResult parse(String label, String content) {
        return LockParser.parse(label, content);
    }

    public static String print(LockDocument document) {
        return LockPrinter.print(document);
    }

    public static Evaluation merge(String base, String left, String right) {
        return merge(base, left, right, List.of());
    }

    public static Evaluation merge(String base, String left, String right,
                                   List<Decision> decisions) {
        ParseResult b = parse("base", base);
        ParseResult l = parse("left", left);
        ParseResult r = parse("right", right);
        String fingerprint = lockmerge.session.SessionService.combinedFingerprint(b, l, r);
        return MergeEngine.evaluate(new MergeEngine.Inputs(
                fingerprint, b, l, r, decisions));
    }

    public static LockDocument documentOf(String content) {
        ParseResult result = parse("doc", content);
        if (result.hasErrors()) {
            throw new IllegalStateException("parse errors: " + result.diagnostics());
        }
        return result.document();
    }
}
