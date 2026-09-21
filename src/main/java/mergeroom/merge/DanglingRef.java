package mergeroom.merge;

/** A root or edge reference whose exact target node is absent from the merged graph. */
public record DanglingRef(String from, String target, String detail) {
}
