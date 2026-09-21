package mergeroom.store;

/** Thrown when an update carries an outdated revision (optimistic locking). */
public class ConcurrentEditException extends Exception {
    public final long expected;
    public final long actual;

    public ConcurrentEditException(long expected, long actual) {
        super("会话已被其他编辑更新（期望版本 " + expected + "，当前版本 " + actual + "）");
        this.expected = expected;
        this.actual = actual;
    }
}
