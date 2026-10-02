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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Peak resident memory while streaming a fixed volume of chunks through each read and write path.
 *
 * <p>JMH's allocation profiler sees only the Java heap. This measures the whole process, so it also counts the native
 * memory icechunk holds and the memory lent to Java as direct buffers. Each mode runs in a fresh JVM: {@code java -cp
 * benchmarks.jar io.earthmover.icechunk.benchmarks.MemoryProbe MODE [chunkMiB] [totalMiB]}, with MODE one of
 * {@code get}, {@code getInto}, {@code getBuffer}, {@code set}, {@code setDirect}.
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
            boolean reading = mode.startsWith("get");
            if (reading) {
                write(repo, chunks, chunkBytes, payload, false);
            }
            System.gc();
            long baseline = rssBytes();
            Sampler sampler = new Sampler();
            sampler.start();
            long start = System.nanoTime();
            long checksum = 0;
            if (reading) {
                try (Session session = repo.readonlySession(Version.branch("main"))) {
                    Store store = session.store();
                    ByteBuffer scratch = ByteBuffer.allocateDirect(chunkBytes);
                    for (int i = 0; i < chunks; i++) {
                        String key = "a/c/" + i;
                        if (mode.equals("get")) {
                            checksum += store.get(key).orElseThrow()[i % chunkBytes];
                        } else if (mode.equals("getInto")) {
                            scratch.clear();
                            store.getInto(key, scratch);
                            checksum += scratch.get(i % chunkBytes);
                        } else {
                            checksum += store.getBuffer(key).orElseThrow().get(i % chunkBytes);
                        }
                    }
                }
            } else {
                write(repo, chunks, chunkBytes, payload, mode.equals("setDirect"));
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            sampler.interrupt();
            sampler.join();
            System.out.printf(
                    "%-9s %4d x %3d MiB  %6.2f s  %7.1f MiB/s  peak RSS above baseline %6.0f MiB  (checksum %d)%n",
                    mode,
                    chunks,
                    chunkBytes >> 20,
                    seconds,
                    (totalBytes >> 20) / seconds,
                    (sampler.peak.get() - baseline) / 1048576.0,
                    checksum);
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void write(Repository repo, int chunks, int chunkBytes, byte[] payload, boolean direct) {
        ByteBuffer directPayload = null;
        if (direct) {
            directPayload = ByteBuffer.allocateDirect(chunkBytes).put(payload);
            directPayload.flip();
        }
        try (Session session = repo.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}".getBytes(UTF_8));
            store.set(
                    "a/zarr.json",
                    StoreBenchmark.arrayMetadata(chunkBytes, chunks).getBytes(UTF_8));
            for (int i = 0; i < chunks; i++) {
                if (direct) {
                    store.set("a/c/" + i, directPayload.duplicate());
                } else {
                    store.set("a/c/" + i, payload);
                }
            }
            session.commit("write");
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
                } catch (IOException e) {
                    return;
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
