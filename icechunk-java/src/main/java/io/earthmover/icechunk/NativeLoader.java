package io.earthmover.icechunk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Finds and loads the {@code icechunk_jni} native library.
 *
 * <p>The first match wins:
 *
 * <ol>
 *   <li>the file named by the {@code icechunk.native.path} system property;
 *   <li>the platform library file inside the directory named by {@code icechunk.native.dir};
 *   <li>an extended build of the library on the classpath under {@code /io/earthmover/icechunk/native-ext/<os>-<arch>/},
 *       shipped by an extension jar that adds its own native methods (see {@link NativeExtensions});
 *   <li>the copy bundled in this jar under {@code /io/earthmover/icechunk/native/<os>-<arch>/};
 *   <li>{@code System.loadLibrary("icechunk_jni")}, which searches {@code java.library.path}.
 * </ol>
 *
 * <p>Bundled copies are extracted to a fresh temporary file in {@code java.io.tmpdir}. Set
 * {@code icechunk.native.tmpdir} to use another directory, for hosts whose temporary directory is mounted
 * {@code noexec}.
 *
 * <p>Each extraction uses a new file name, so the library can be loaded by more than one class loader in the same
 * JVM, which the JVM refuses for a single file.
 */
final class NativeLoader {
    static final String LIBRARY = "icechunk_jni";

    private NativeLoader() {}

    static void load() {
        String path = System.getProperty("icechunk.native.path");
        if (path != null) {
            System.load(Paths.get(path).toAbsolutePath().toString());
            return;
        }
        String dir = System.getProperty("icechunk.native.dir");
        if (dir != null) {
            System.load(Paths.get(dir, System.mapLibraryName(LIBRARY))
                    .toAbsolutePath()
                    .toString());
            return;
        }
        for (String bundle : new String[] {"native-ext", "native"}) {
            String resource = bundle + "/" + platform() + "/" + System.mapLibraryName(LIBRARY);
            try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
                if (in != null) {
                    System.load(extract(in).toString());
                    return;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot extract the icechunk native library", e);
            }
        }
        System.loadLibrary(LIBRARY);
    }

    private static Path extract(InputStream in) throws IOException {
        String tmp = System.getProperty("icechunk.native.tmpdir", System.getProperty("java.io.tmpdir"));
        Path file = Files.createTempFile(Paths.get(tmp), "icechunk_jni-", "-" + System.mapLibraryName(LIBRARY));
        file.toFile().deleteOnExit();
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    /** The bundle directory name for this JVM, for example {@code osx-aarch_64} or {@code linux-x86_64}. */
    static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String osName;
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            osName = "osx";
        } else if (os.startsWith("windows")) {
            osName = "windows";
        } else if (os.startsWith("linux")) {
            osName = "linux";
        } else {
            osName = os.replaceAll("[^a-z0-9]", "");
        }
        String archName;
        switch (arch) {
            case "amd64":
            case "x86_64":
                archName = "x86_64";
                break;
            case "aarch64":
            case "arm64":
                archName = "aarch_64";
                break;
            default:
                archName = arch.replaceAll("[^a-z0-9_]", "");
        }
        return osName + "-" + archName;
    }
}
