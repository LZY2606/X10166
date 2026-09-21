package lockmerge.merge;

import java.time.Instant;

/**
 * A human adjudication. It is bound to the fingerprints of the three inputs
 * at decision time; once any input changes, the decision no longer applies.
 */
public record Decision(String id, String conflictId, String optionId,
                       String inputFingerprint, String baseFingerprint,
                       String leftFingerprint, String rightFingerprint,
                       String chosenSummary, Instant createdAt) {

    public boolean appliesTo(String inputFp, String baseFp, String leftFp, String rightFp) {
        return safe(inputFingerprint).equals(safe(inputFp))
                && safe(baseFingerprint).equals(safe(baseFp))
                && safe(leftFingerprint).equals(safe(leftFp))
                && safe(rightFingerprint).equals(safe(rightFp));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
