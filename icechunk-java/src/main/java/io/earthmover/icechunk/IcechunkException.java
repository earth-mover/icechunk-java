package io.earthmover.icechunk;

/**
 * An error reported by icechunk.
 *
 * <p>Misuse of the API is reported with the standard exceptions instead: {@link IllegalStateException} for a closed
 * object, {@link IllegalArgumentException} for a value the native layer rejects.
 */
public class IcechunkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IcechunkException(String message) {
        super(message);
    }

    public IcechunkException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Map an error kind reported by the native layer to an exception. */
    static RuntimeException fromNative(String kind, String message) {
        switch (kind) {
            case "CONFLICT":
                return new ConflictException(message);
            case "INVALID_ARGUMENT":
                return new IllegalArgumentException(message);
            case "CLOSED":
                return new IllegalStateException(message);
            case "RUNTIME_THREAD":
                return new IllegalStateException(message);
            default:
                return new IcechunkException(message);
        }
    }
}
