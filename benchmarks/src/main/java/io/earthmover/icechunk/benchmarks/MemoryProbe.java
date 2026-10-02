package io.earthmover.icechunk.benchmarks;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Store;
import io.earthmover.icechunk.Version;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Peak resident memory while streaming a fixed volume of chunks through each read and write path.
 *
 * <p>JMH's allocation profiler sees only the Java heap. This measures the whole process, so it also counts the native
 * memory icechunk holds. Each mode runs in a fresh JVM: {@code java -cp
 * benchmarks.jar io.earthmover.icechunk.benchmarks.MemoryProbe MODE [chunkMiB] [totalMiB]}, with MODE one of
 * {@code get}, {@code set}, {@code setDirect}.
 */
public final class MemoryProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int chunkBytes = (args.length > 1 ? Integer.parseInt(args[1]) : 4) << 20;
        long totalBytes = (long) (args.length > 2 ? Integer.parseInt(args[2]) : 1024) << 20;
        int chunks = (int) (totalBytes / chunkBytes);

        Path dir = Files.createTempDirectory("icechunk-memory");
        byte[] payload = new byte[chunkBytes];
        ThreadLocalRandom.current().nextBytes(payload);
        try (Storage storage = Storage.localFilesystem(dir);
                Repository repo = Repository.create(storage)) {
            boolean reading = mode.equals("get");
            if (reading) {
                Fixtures.writeArray(repo, chunks, payload, false);
            }
            System.gc();
            long baseline = rssBytes();
            PeakMemory peak = PeakMemory.start();
            long start = System.nanoTime();
            long checksum = 0;
            if (reading) {
                try (Session session = repo.readonlySession(Version.branch("main"))) {
                    Store store = session.store();
                    for (int i = 0; i < chunks; i++) {
                        checksum += store.get(Fixtures.key(i)).get()[i % chunkBytes];
                    }
                }
            } else {
                Fixtures.writeArray(repo, chunks, payload, mode.equals("setDirect"));
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            long peakBytes = peak.stop();
            System.out.printf(
                    "%-9s %4d x %3d MiB  %6.2f s  %7.1f MiB/s  peak RSS above baseline %6.0f MiB  (checksum %d)%n",
                    mode,
                    chunks,
                    chunkBytes >> 20,
                    seconds,
                    (totalBytes >> 20) / seconds,
                    (peakBytes - baseline) / 1048576.0,
                    checksum);
        } finally {
            Fixtures.deleteTree(dir);
        }
    }

    /** Resident set size of this process, from {@code ps}, which works on Linux and macOS. */
    static long rssBytes() throws IOException {
        Process ps = new ProcessBuilder(
                        "ps",
                        "-o",
                        "rss=",
                        "-p",
                        Long.toString(ProcessHandle.current().pid()))
                .start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(ps.getInputStream(), UTF_8))) {
            return Long.parseLong(out.readLine().trim()) * 1024;
        }
    }

    /**
     * Peak resident memory over a stretch of the run. On Linux the kernel tracks it exactly: writing 5 to
     * {@code /proc/self/clear_refs} resets the high-water mark, and {@code VmHWM} reports it. Elsewhere a thread samples
     * {@code ps}, which can miss short peaks and costs a process spawn per sample.
     */
    private abstract static class PeakMemory {
        abstract long stop() throws IOException, InterruptedException;

        static PeakMemory start() throws IOException {
            Path clearRefs = Paths.get("/proc/self/clear_refs");
            if (Files.isWritable(clearRefs)) {
                Files.write(clearRefs, "5".getBytes(UTF_8));
                return new PeakMemory() {
                    @Override
                    long stop() throws IOException {
                        for (String line : Files.readAllLines(Paths.get("/proc/self/status"), UTF_8)) {
                            if (line.startsWith("VmHWM:")) {
                                return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                            }
                        }
                        throw new IOException("no VmHWM in /proc/self/status");
                    }
                };
            }
            Sampler sampler = new Sampler();
            sampler.start();
            return new PeakMemory() {
                @Override
                long stop() throws InterruptedException {
                    sampler.interrupt();
                    sampler.join();
                    return sampler.peak.get();
                }
            };
        }
    }

    private static final class Sampler extends Thread {
        final AtomicLong peak = new AtomicLong();

        Sampler() {
            setDaemon(true);
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    peak.accumulateAndGet(rssBytes(), Math::max);
                    Thread.sleep(20);
                } catch (IOException | InterruptedException e) {
                    return;
                }
            }
        }
    }
}
