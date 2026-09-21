package lockmerge.platform;

/** Thrown when a platform condition expression cannot be parsed. */
public class PlatformSyntaxException extends RuntimeException {

    public PlatformSyntaxException(String message) {
        super(message);
    }
}
