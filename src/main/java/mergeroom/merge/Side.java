package mergeroom.merge;

/** Branch side in a three-way merge. */
public enum Side {
    LEFT, RIGHT;

    public String label() {
        return this == LEFT ? "left" : "right";
    }
}
