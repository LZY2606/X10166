package dev.lockmerge.merge;

/**
 * A merge item the user must adjudicate. Each conflict carries stable IDs and
 * enough payload to describe the base/left/right alternatives.
 *
 * <p>Decisions bind to the SHA-256 fingerprints of the three raw inputs; the
 * engine never reapplies a decision whose fingerprint triple changed.
 */
public record Conflict(String id,
                       Type type,
                       String title,
                       String detail,
                       Object base,
                       Object left,
                       Object right) {

    public enum Type {
        NODE_MODIFY,
        NODE_DELETE,
        EDGE_DIVERGE,
        INTEGRITY
    }
}
