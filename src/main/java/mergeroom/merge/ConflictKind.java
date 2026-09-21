package mergeroom.merge;

public enum ConflictKind {
    /** Root dependency changed on both sides (version/condition) or deleted vs modified. */
    ROOT,
    /** Node deleted on one side, modified on the other; or added differently on both. */
    NODE,
    /** A scalar field of one node changed on both sides. */
    FIELD,
    /** A require edge changed on both sides. */
    EDGE,
    /** Same source coordinate + version carries different integrity digests. */
    INTEGRITY
}
