package io.earthmover.icechunk.zarr;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Locates the optional resources some tests need, skipping those tests when a resource is missing. */
final class TestEnvironment {
    private TestEnvironment() {}

    /**
     * Skip the test unless {@code available}. With {@code -Dicechunk.tests.strict=true}, as in CI, fail instead, so a
     * missing resource cannot silently turn a test suite green.
     */
    static void require(boolean available, String what) {
        if (Boolean.getBoolean("icechunk.tests.strict") && !available) {
            fail(what + " is required when icechunk.tests.strict is set");
        }
        assumeTrue(available, what + " is not available");
    }

    /** A private copy of one of icechunk's compatibility repositories. */
    static Path fixture(String name, Path into) throws IOException {
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
    static boolean hasUv() {
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

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
