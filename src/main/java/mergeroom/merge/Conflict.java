package mergeroom.merge;

/**
 * A pending or decided conflict. Identifiers are deterministic for fixed inputs
 * so that bound verdicts can be re-applied across recomputations.
 */
public record Conflict(
        String id,
        ConflictKind kind,
        String subject,
        String description,
        String leftOption,
        String rightOption,
        Side resolution,
        boolean active,
        boolean danglingAfterResolution) {

    public Conflict withRecompute(Side newResolution, boolean nowActive, boolean dangling) {
        return new Conflict(id, kind, subject, description, leftOption, rightOption,
                newResolution, nowActive, dangling);
    }

    public boolean unresolved() {
        return resolution == null;
    }
}
