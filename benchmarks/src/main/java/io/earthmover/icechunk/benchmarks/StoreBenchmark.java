package io.earthmover.icechunk.benchmarks;

import static java.nio.charset.StandardCharsets.UTF_8;

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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
 * <p>Reads go to a committed snapshot through a read-only session, writes to a writable session. Each read picks one of
 * {@link #CHUNKS} chunks at random so the benchmark does not measure a single hot key.
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

    private Path dir;
    private Storage store;
    private Repository repo;
    private Session reader;
    private Session writer;
    private Store readStore;
    private Store writeStore;
    private byte[] payload;
    private ByteBuffer directPayload;
    private List<String> allKeys;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        if (storage.equals("local")) {
            dir = Files.createTempDirectory("icechunk-bench");
            store = Storage.localFilesystem(dir);
        } else {
            store = Storage.inMemory();
        }
        repo = Repository.create(store);
        payload = new byte[chunkBytes];
        ThreadLocalRandom.current().nextBytes(payload);
        directPayload = ByteBuffer.allocateDirect(chunkBytes).put(payload);
        directPayload.flip();
        try (Session session = repo.writableSession("main")) {
            Store s = session.store();
            s.set("zarr.json", "{\"zarr_format\":3,\"node_type\":\"group\",\"attributes\":{}}".getBytes(UTF_8));
            s.set("a/zarr.json", arrayMetadata(chunkBytes).getBytes(UTF_8));
            for (int i = 0; i < CHUNKS; i++) {
                s.set(key(i), payload);
            }
            session.commit("setup");
        }
        reader = repo.readonlySession(Version.branch("main"));
        readStore = reader.store();
        writer = repo.writableSession("main");
        writeStore = writer.store();
        allKeys = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) {
            allKeys.add(key(i));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        writer.close();
        reader.close();
        repo.close();
        store.close();
        if (dir != null) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    static String key(int i) {
        return "a/c/" + i;
    }

    static String arrayMetadata(int chunkBytes) {
        return arrayMetadata(chunkBytes, CHUNKS);
    }

    /** A 1-d uint8 array with one chunk per key, so every key is a valid chunk. */
    static String arrayMetadata(int chunkBytes, int chunks) {
        return "{\"zarr_format\":3,\"node_type\":\"array\",\"shape\":[" + (long) chunkBytes * chunks
                + "],\"data_type\":\"uint8\",\"chunk_grid\":{\"name\":\"regular\",\"configuration\":"
                + "{\"chunk_shape\":[" + chunkBytes + "]}},\"chunk_key_encoding\":{\"name\":\"default\","
                + "\"configuration\":{\"separator\":\"/\"}},\"fill_value\":0,\"codecs\":[{\"name\":\"bytes\"}],"
                + "\"attributes\":{}}";
    }

    private static String randomKey() {
        return key(ThreadLocalRandom.current().nextInt(CHUNKS));
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

    /** As {@link #get}, without copying: a buffer over icechunk's memory. */
    @Benchmark
    public Optional<ByteBuffer> getBuffer() {
        return readStore.getBuffer(randomKey());
    }

    /** As {@link #get}, copying into a reused buffer instead of allocating. */
    @Benchmark
    public int getInto(Scratch scratch) {
        scratch.buffer.clear();
        return readStore.getInto(randomKey(), scratch.buffer);
    }

    /** A per-thread destination buffer for {@link #getInto}. */
    @State(Scope.Thread)
    public static class Scratch {
        ByteBuffer buffer;

        @Setup
        public void allocate(StoreBenchmark benchmark) {
            buffer = ByteBuffer.allocateDirect(benchmark.chunkBytes);
        }
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
    public List<Optional<byte[]>> getMany() {
        return readStore.getMany(allKeys);
    }

    @Benchmark
    public void set() {
        writeStore.set(randomKey(), payload);
    }

    /** As {@link #set}, from a direct buffer, which large values are read from in place. */
    @Benchmark
    public void setDirect() {
        writeStore.set(randomKey(), directPayload.duplicate());
    }
}
