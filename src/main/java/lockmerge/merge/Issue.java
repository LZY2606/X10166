package lockmerge.merge;

/**
 * A blocking integrity/structure problem in the merged graph.
 *
 * @param kind     DANGLING_EDGE / DANGLING_ROOT / INTEGRITY_MISMATCH
 * @param message  human-readable explanation
 */
public record Issue(String kind, String message) {
}
