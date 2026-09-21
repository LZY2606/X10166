package lockmerge.merge;

/** Thrown when an adjudication references an unknown conflict/option. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
