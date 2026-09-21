package dev.lockmerge;

import dev.lockmerge.lock.LockParser;
import dev.lockmerge.merge.MergeEngine;
import dev.lockmerge.merge.MergeService;
import dev.lockmerge.merge.Validator;
import dev.lockmerge.model.LockDocument;

/** Helpers for building parsed, validated merge inputs from lockfile text. */
public final class Fixtures {

    private Fixtures() {
    }

    public static LockDocument doc(String text) {
        LockParser.Result result = LockParser.parse(text);
        if (result.hasErrors()) {
            throw new IllegalStateException("fixture parse error:\n" + text
                    + "\n" + result.diagnostics);
        }
        Validator.Report report = Validator.validate(result.document);
        if (report.hasErrors()) {
            throw new IllegalStateException("fixture validation error:\n" + text
                    + "\n" + report.diagnostics);
        }
        return result.document;
    }

    public static LockParser.Result parse(String text) {
        return LockParser.parse(text);
    }

    public static MergeEngine.SideInput side(String text) {
        LockParser.Result result = LockParser.parse(text);
        if (result.hasErrors()) {
            throw new IllegalStateException("fixture parse error: " + result.diagnostics);
        }
        return new MergeEngine.SideInput(MergeService.fingerprint(text),
                result.document, result.diagnostics, Validator.validate(result.document));
    }

    public static MergeEngine.Inputs inputs(String base, String left, String right) {
        return new MergeEngine.Inputs(side(base), side(left), side(right));
    }

    public static String fp(String text) {
        return MergeService.fingerprint(text);
    }

    public static String digest(String seed) {
        return "sha256:" + MergeService.fingerprint(seed).substring(7);
    }
}
