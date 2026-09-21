package mergeroom.merge;

import java.util.Objects;

/**
 * Three-way merge for an immutable value.
 */
public final class ThreeWay {

    public enum Outcome {
        /** Both sides agree: take the shared value. */
    }

    private ThreeWay() {
    }

    /**
     * @param base   value in the base side (null means absent)
     * @param left   value on the left (null means absent)
     * @param right  value on the right (null means absent)
     * @return merged value (null means absent) plus a conflict flag
     */
    public record Result(Object value, boolean conflict) {
    }

    public static Result merge(Object base, Object left, Object right) {
        if (Objects.equals(left, right)) {
            return new Result(left, false);
        }
        if (Objects.equals(base, left)) {
            return new Result(right, false);
        }
        if (Objects.equals(base, right)) {
            return new Result(left, false);
        }
        // Divergent edits; tentatively keep the left value.
        return new Result(left, true);
    }

    public static Object resolve(Object base, Object left, Object right, Side side) {
        return side == Side.LEFT ? left : right;
    }
}
