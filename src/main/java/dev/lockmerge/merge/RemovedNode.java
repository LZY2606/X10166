package dev.lockmerge.merge;

/** Explanation for a node removed by reachability cleanup. */
public record RemovedNode(String id, String reason) {
}
