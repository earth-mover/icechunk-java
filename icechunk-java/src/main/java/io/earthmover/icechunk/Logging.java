package io.earthmover.icechunk;

import java.util.Objects;

/**
 * icechunk's own log output, written to standard error (or standard output when {@code ICECHUNK_LOG_TO_STDOUT} is
 * set). Nothing is logged until one of these methods is called.
 */
public final class Logging {
    private Logging() {}

    /** Log with the filter in the {@code ICECHUNK_LOG} environment variable, or warnings only if it is unset. */
    public static void initialize() {
        Native.initializeLogs(null);
    }

    /**
     * Log with {@code filter}, in the {@code tracing} crate's {@code EnvFilter} syntax, such as {@code "debug"} or
     * {@code "icechunk=trace,warn"}. Calling it again replaces the filter.
     *
     * @throws IllegalArgumentException if {@code filter} has a directive the syntax does not allow
     */
    public static void initialize(String filter) {
        Native.initializeLogs(Objects.requireNonNull(filter, "filter"));
    }
}
