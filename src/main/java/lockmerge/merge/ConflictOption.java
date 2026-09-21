package lockmerge.merge;

import java.util.List;

/** One selectable resolution for a conflict. */
public record ConflictOption(String id, String label, String side,
                             String nodeRef, boolean recommended) {

    public static final String LEFT = "left";
    public static final String RIGHT = "right";
    public static final String BASE = "base";
    public static final String DELETE = "delete";
}
