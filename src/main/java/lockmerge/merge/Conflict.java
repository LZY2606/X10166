package lockmerge.merge;

/** 一项待决冲突。choice 为 "left" / "right" / null（未裁决）。 */
public final class Conflict {
    public final String id;
    public final String nodeKey;
    public final String field;
    public final String leftValue;
    public final String rightValue;
    public final String description;
    public final String choice; // null 表示未裁决

    public Conflict(String id, String nodeKey, String field, String leftValue, String rightValue,
                    String description, String choice) {
        this.id = id;
        this.nodeKey = nodeKey;
        this.field = field;
        this.leftValue = leftValue;
        this.rightValue = rightValue;
        this.description = description;
        this.choice = choice;
    }

    public Conflict withChoice(String newChoice) {
        return new Conflict(id, nodeKey, field, leftValue, rightValue, description, newChoice);
    }
}
