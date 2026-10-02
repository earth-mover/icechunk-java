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
        try {
            Class.forName(Native.class.getName(), true, Native.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Close a handle the extension registered for one of its own objects. Closing twice is harmless. */
    public static void close(long handle) {
        Native.close(handle);
    }

    /**
     * Take ownership of a handle the extension registered for one of its own objects. The handle is closed by
     * {@link Handle#close}, and on Java 9 and later also once the {@code Handle} is unreachable, as this library's own
     * objects are.
     *
     * @param name what the handle holds, for the message when it is used after closing
     */
    public static Handle handle(long handle, String name) {
        return new Handle(handle, name);
    }

    /**
     * Keep {@code handle} reachable until this call. An extension calls it in a {@code finally} block after every
     * native method that takes {@link Handle#get()}, so the handle cannot be released while the call uses it.
     */
    public static void reachabilityFence(Handle handle) {
        HandleCleaner.reachabilityFence(handle);
    }

    /** An extension object's entry in the native handle table. */
    public static final class Handle extends NativeHandle {
        private final String name;

        private Handle(long handle, String name) {
            super(handle);
            this.name = name;
        }

        /**
         * The handle to pass to a native method.
         *
         * @throws IllegalStateException if the handle is closed
         */
        public long get() {
            if (isClosed()) {
                throw new IllegalStateException(name + " is closed");
            }
            return handle();
        }
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
