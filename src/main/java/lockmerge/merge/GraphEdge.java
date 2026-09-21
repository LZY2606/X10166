package lockmerge.merge;

import java.util.List;

/** An edge present in the merged graph (from a reachable node). */
public record GraphEdge(String fromRef, String toRef, String platforms,
                        boolean targetReachable, List<String> origins)
        implements Comparable<GraphEdge> {

    @Override
    public int compareTo(GraphEdge other) {
        int c = fromRef.compareTo(other.fromRef);
        if (c != 0) {
            return c;
        }
        c = toRef.compareTo(other.toRef);
        if (c != 0) {
            return c;
        }
        String a = platforms == null ? "" : platforms;
        String b = other.platforms == null ? "" : other.platforms;
        return a.compareTo(b);
    }
}
