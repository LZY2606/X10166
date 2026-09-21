package mergeroom.merge;

/** A node removed by reachability cleanup, with a human-readable explanation. */
public record RemovedNode(String key, String reason) {
}
