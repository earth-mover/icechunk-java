package io.earthmover.icechunk;

/**
 * Support for libraries that add native operations on top of this one, such as an Arraylake client.
 *
 * <p>This is not an API for applications. An extension builds a single native library that contains the
 * {@code icechunk-jni} crate plus its own JNI exports, and ships it in its jar under
 * {@code io/earthmover/icechunk/native-ext/<os>-<arch>/}, where the loader looks first. Objects the extension's native
 * code registers share the handle table with this library, so the handles it returns can be wrapped as ordinary
 * {@link Repository} and {@link Storage} objects.
 *
 * <p>Extension native methods take the callback as a plain {@code Object} and follow the same contract as the
 * methods on {@code Native}: start the operation, return a task id, report the result through the callback.
 */
public final class NativeExtensions {
    /** Starts one native operation, passing it the callback object, and returns the task id. */
    @FunctionalInterface
    public interface Starter {
        long start(Object callback);
    }

    private NativeExtensions() {}

    /** Load the native library, if it is not loaded yet. Call before the first extension native method. */
    public static void ensureLoaded() {
        Native.cancel(0);
    }

    /** Run an operation whose result is a handle or other {@code long}. */
    public static long runLong(Starter starter) {
        return NativeCall.runLong(starter::start);
    }

    /** Run an operation whose result is a string, or null. */
    public static String runString(Starter starter) {
        return NativeCall.runString(starter::start);
    }

    /** Run an operation whose result is a list of strings. */
    public static java.util.List<String> runStrings(Starter starter) {
        return NativeCall.runStrings(starter::start);
    }

    /** Take ownership of a repository handle the native extension registered. */
    public static Repository repository(long handle) {
        return new Repository(handle);
    }

    /** Take ownership of a storage handle the native extension registered. */
    public static Storage storage(long handle, String description) {
        return new Storage(handle, description);
    }
}
