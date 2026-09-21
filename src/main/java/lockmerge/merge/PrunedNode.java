package lockmerge.merge;

/** 被可达性清理移除的节点及原因。 */
public final class PrunedNode {
    public final String key;
    public final String reason;

    public PrunedNode(String key, String reason) {
        this.key = key;
        this.reason = reason;
    }
}
