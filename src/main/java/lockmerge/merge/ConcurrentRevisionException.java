package lockmerge.merge;

/** Thrown when a mutation targets a stale session revision (optimistic lock). */
public class ConcurrentRevisionException extends RuntimeException {

    public ConcurrentRevisionException(String message) {
        super(message);
    }
}
