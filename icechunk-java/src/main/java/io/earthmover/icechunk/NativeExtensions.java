package io.earthmover.icechunk;

/**
 * Support for libraries that add native operations on top of this one, such as an Arraylake client.
 *
 * <p>This is not an API for applications. An extension builds a single native library that contains the
 * {@code icechunk-jni} crate plus its own JNI exports, and ships it in its jar under
 * {@code io/earthmover/icechunk/native-ext/<os>-<arch>/}, where the loader looks first. Objects the extension's native
 * code registers share the handle table with this library, so the handles it returns can be wrapped as ordinary
 * {@link Repository} and {@link Storage} objects.
 */
public final class NativeExtensions {
    private NativeExtensions() {}

    /** Load the native library, if it is not loaded yet. Call before the first extension native method. */
    public static void ensureLoaded() {
        Native.bufferOutstanding();
    }

    /** Close a handle the extension registered for one of its own objects. Closing twice is harmless. */
    public static void close(long handle) {
        Native.close(handle);
    }

    /** The JSON form of {@code options}, which the native side parses with {@code icechunk_jni::ext::repository_options}. */
    public static String toJson(RepositoryOptions options) {
        return options.toJson();
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
