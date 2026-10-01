package io.earthmover.icechunk;

/**
 * A commit failed because the branch moved since the session started.
 *
 * <p>Another writer committed to the same branch first. Open a new writable session and redo the changes.
 */
public class ConflictException extends IcechunkException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
