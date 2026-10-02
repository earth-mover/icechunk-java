package io.earthmover.icechunk.benchmarks;

import io.earthmover.icechunk.ByteRange;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Store;
import io.earthmover.icechunk.Version;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-operation cost of the Store API.
 *
 * <p>Reads go to a committed snapshot through a read-only session. Each read picks one of {@link #CHUNKS} chunks at
 * random so the benchmark does not measure a single hot key. Writes go to a writable session over a fresh storage
 * each iteration, so the values written in earlier iterations do not pile up and skew later ones.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class StoreBenchmark {
    static final int CHUNKS = 64;

    @Param({"memory", "local"})
    public String storage;

    @Param({"64", "65536", "1048576"})
    public int chunkBytes;

    private byte[] payload;
    private ByteBuffer directPayload;
    private List<String> allKeys;

    private Fixture reads;
    private Session reader;
    private Store readStore;

    private Fixture writes;
    private Session writer;
    private Store writeStore;

    /** A storage and repository, in memory or in a temporary directory. */
    private final class Fixture implements AutoCloseable {
        final Path dir;
        final Storage storage;
        final Repository repo;

        Fixture() throws IOException {
            if (StoreBenchmark.this.storage.equals("local")) {
                dir = Files.createTempDirectory("icechunk-bench");
                storage = Storage.localFilesystem(dir);
            } else {
                dir = null;
                storage = Storage.inMemory();
            }
            repo = Repository.create(storage);
        }

        @Override
        public void close() throws IOException {
            repo.close();
            storage.close();
            if (dir != null) {
                Fixtures.deleteTree(dir);
            }
        }
    }

    @Setup(Level.Trial)
    public void setUpReads() throws IOException {
        payload = new byte[chunkBytes];
        ThreadLocalRandom.current().nextBytes(payload);
        directPayload = Fixtures.directCopy(payload);
        allKeys = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) {
            allKeys.add(Fixtures.key(i));
        }
        reads = new Fixture();
        Fixtures.writeArray(reads.repo, CHUNKS, payload, false);
        reader = reads.repo.readonlySession(Version.branch("main"));
        readStore = reader.store();
    }

    @TearDown(Level.Trial)
    public void tearDownReads() throws IOException {
        reader.close();
        reads.close();
    }

    @Setup(Level.Iteration)
    public void setUpWrites() throws IOException {
        writes = new Fixture();
        writer = writes.repo.writableSession("main");
        writeStore = writer.store();
        Fixtures.writeMetadata(writeStore, chunkBytes, CHUNKS);
    }

    @TearDown(Level.Iteration)
    public void tearDownWrites() throws IOException {
        writer.close();
        writes.close();
    }

    private static String randomKey() {
        return Fixtures.key(ThreadLocalRandom.current().nextInt(CHUNKS));
    }

    /** The cheapest call there is: the fixed cost of crossing into native code and back. */
    @Benchmark
    public boolean callOverhead() {
        return reader.isReadOnly();
    }

    @Benchmark
    public Optional<byte[]> get() {
        return readStore.get(randomKey());
    }

    @Benchmark
    public Optional<byte[]> getSuffix() {
        return readStore.get(randomKey(), ByteRange.suffix(16));
    }

    @Benchmark
    public boolean exists() {
        return readStore.exists(randomKey());
    }

    /** All {@link #CHUNKS} chunks in one call; divide by 64 for the per-chunk cost. */
    @Benchmark
    public List<Optional<byte[]>> getPartialValues() {
        return readStore.getPartialValues(allKeys);
    }

    @Benchmark
    public void set() {
        writeStore.set(randomKey(), ByteBuffer.wrap(payload));
    }

    /** As {@link #set}, from a direct buffer, which large values are read from in place. */
    @Benchmark
    public void setDirect() {
        writeStore.set(randomKey(), directPayload.duplicate());
    }
}
