package io.earthmover.icechunk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locates the optional resources some tests need, skipping those tests when a resource is missing. The other modules
 * use it through this module's test jar.
 */
public final class TestEnvironment {
    /** Maven runs each module's tests in the module directory. */
    private static final Path PYTHON_SCRIPTS = Paths.get(System.getProperty("user.dir"))
            .getParent()
            .resolve("tests")
            .resolve("python");

    private TestEnvironment() {}

    /**
     * Skip the test unless {@code available}. With {@code -Dicechunk.tests.strict=true}, as in CI, fail instead, so a
     * missing resource cannot silently turn a test suite green.
     */
    public static void require(boolean available, String what) {
        if (Boolean.getBoolean("icechunk.tests.strict") && !available) {
            fail(what + " is required when icechunk.tests.strict is set");
        }
        assumeTrue(available, what + " is not available");
    }

    /** A private copy of one of icechunk's compatibility repositories. */
    public static Path fixture(String name, Path into) throws IOException {
        String dir = System.getProperty("icechunk.fixtures.dir", "");
        Path source = Paths.get(dir, name);
        require(!dir.isEmpty() && Files.isDirectory(source), "icechunk fixture " + name);
        Path target = into.resolve(name);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy);
                }
            }
        }
        return target;
    }

    /** The {@code uv} executable, if it is on the PATH. */
    private static boolean hasUv() {
        try {
            Process process = new ProcessBuilder("uv", "--version")
                    .redirectErrorStream(true)
                    .start();
            readAll(process.getInputStream());
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Run {@code script} from {@code tests/python} with {@code args} through {@code uv}, with the latest icechunk, zarr
     * and numpy releases, and return what it printed. Skips the test without {@code uv}, as {@link #require} does, and
     * fails it if the script exits with an error.
     */
    public static String python(String script, String... args) throws IOException, InterruptedException {
        return pythonWith(Collections.emptyList(), script, args);
    }

    /** {@link #python}, with the latest releases of {@code packages} as well. */
    public static String pythonWith(List<String> packages, String script, String... args)
            throws IOException, InterruptedException {
        require(hasUv(), "uv");
        List<String> command = new ArrayList<>(
                Arrays.asList("uv", "run", "--no-project", "--with", "icechunk", "--with", "zarr", "--with", "numpy"));
        for (String name : packages) {
            command.add("--with");
            command.add(name);
        }
        command.add("python");
        command.add(PYTHON_SCRIPTS.resolve(script).toString());
        command.addAll(Arrays.asList(args));
        Path stderr = Files.createTempFile("icechunk-python-", ".log");
        try {
            Process process =
                    new ProcessBuilder(command).redirectError(stderr.toFile()).start();
            String output = new String(readAll(process.getInputStream()), UTF_8);
            int status = process.waitFor();
            assertEquals(0, status, () -> script + " failed:\n" + output + readQuietly(stderr));
            return output;
        } finally {
            Files.deleteIfExists(stderr);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return new String(Files.readAllBytes(file), UTF_8);
        } catch (IOException e) {
            return "(stderr unavailable: " + e + ")";
        }
    }
}
