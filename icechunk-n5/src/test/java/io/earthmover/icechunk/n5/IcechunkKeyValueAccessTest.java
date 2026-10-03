package io.earthmover.icechunk.n5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import io.earthmover.icechunk.IcechunkException;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import org.janelia.saalfeldlab.n5.ByteArrayDataBlock;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Exception.N5IOException;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.ShortArrayDataBlock;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5FactoryWithCache;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** n5-zarr's Zarr v3 reader and writer over {@link IcechunkKeyValueAccess}. */
class IcechunkKeyValueAccessTest {
    private Storage storage;
    private Repository repo;

    @BeforeEach
    void open() {
        storage = Storage.inMemory();
        repo = Repository.create(storage);
    }

    @AfterEach
    void close() {
        repo.close();
        storage.close();
    }

    static N5Writer writer(Session session) {
        return new ZarrV3KeyValueWriter(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
    }

    private static N5Reader reader(Session session) {
        return new ZarrV3KeyValueReader(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
    }

    private static short[] ramp(int n, int start) {
        short[] values = new short[n];
        for (int i = 0; i < n; i++) {
            values[i] = (short) (start + i);
        }
        return values;
    }

    @Test
    void groupsDatasetsAndAttributesRoundTrip() {
        try (Session session = repo.writableSession("main")) {
            N5Writer n5 = writer(session);
            n5.createGroup("a group");
            n5.setAttribute("a group", "units", "nm");
            DatasetAttributes attributes = n5.createDataset(
                    "a group/raw", new long[] {10, 8}, new int[] {5, 4}, DataType.UINT16, new RawCompression());
            n5.writeChunk(
                    "a group/raw",
                    attributes,
                    new ShortArrayDataBlock(new int[] {5, 4}, new long[] {1, 1}, ramp(20, 0)));
            session.commit("written by n5");
        }

        try (Session session = repo.readonlySession(Version.branch("main"))) {
            N5Reader n5 = reader(session);
            assertTrue(n5.exists("a group"));
            assertTrue(n5.datasetExists("a group/raw"));
            assertFalse(n5.exists("missing"));
            assertEquals("nm", n5.getAttribute("a group", "units", String.class));
            assertArrayEquals(new String[] {"raw"}, n5.list("a group"));

            DatasetAttributes attributes = n5.getDatasetAttributes("a group/raw");
            assertArrayEquals(new long[] {10, 8}, attributes.getDimensions());
            DataBlock<?> chunk = n5.readChunk("a group/raw", attributes, 1, 1);
            assertArrayEquals(ramp(20, 0), (short[]) chunk.getData());
            assertNull(n5.readChunk("a group/raw", attributes, 0, 0));
            assertTrue(session.store().exists("a group/raw/zarr.json"));

            IcechunkKeyValueAccess kva = new IcechunkKeyValueAccess(session);
            assertFalse(kva.isFile("a group"));
            assertArrayEquals(new String[] {"raw"}, kva.listDirectories("a group"));
            assertArrayEquals(new String[0], kva.listDirectories("a group/raw"));
        }
    }

    @Test
    void shardedDatasetsReadChunksByRange() {
        DatasetAttributes sharded = new ZarrV3DatasetAttributes(
                new long[] {8, 8}, new int[] {4, 4}, new int[] {2, 2}, DataType.UINT8, new RawCompression());
        try (Session session = repo.writableSession("main")) {
            N5Writer n5 = writer(session);
            n5.createDataset("sharded", sharded);
            // Each shard is rewritten as its chunks arrive, so this also covers reading back a partial shard.
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    byte[] data = new byte[4];
                    Arrays.fill(data, (byte) (10 * x + y));
                    n5.writeChunk(
                            "sharded", sharded, new ByteArrayDataBlock(new int[] {2, 2}, new long[] {x, y}, data));
                }
            }
            session.commit("sharded");
        }

        try (Session session = repo.readonlySession(Version.branch("main"))) {
            N5Reader n5 = reader(session);
            DatasetAttributes attributes = n5.getDatasetAttributes("sharded");
            byte[] expected = new byte[4];
            Arrays.fill(expected, (byte) 31);
            assertArrayEquals(
                    expected, (byte[]) n5.readChunk("sharded", attributes, 3, 1).getData());
            assertEquals(
                    4,
                    session.store().listPrefix("sharded").stream()
                            .filter(key -> key.startsWith("sharded/c/"))
                            .count());
        }
    }

    @Test
    void removeDeletesAGroupAndEverythingBelowIt() {
        try (Session session = repo.writableSession("main")) {
            N5Writer n5 = writer(session);
            DatasetAttributes attributes =
                    n5.createDataset("g/raw", new long[] {4}, new int[] {4}, DataType.UINT16, new RawCompression());
            n5.writeChunk("g/raw", attributes, new ShortArrayDataBlock(new int[] {4}, new long[] {0}, ramp(4, 0)));
            assertTrue(n5.remove("g"));
            assertFalse(n5.exists("g/raw"));
            assertEquals(Collections.singletonList("zarr.json"), session.store().listDir(""));
        }
    }

    @Test
    void namesAreStoredAsWritten() {
        try (Session session = repo.writableSession("main")) {
            N5Writer n5 = writer(session);
            n5.createGroup("raw/c");
            n5.createGroup("x#y%z");
            assertTrue(session.store().exists("x#y%z/zarr.json"));
            assertArrayEquals(new String[] {"c"}, n5.list("raw"));

            assertTrue(n5.remove("raw/c"));
            assertFalse(n5.exists("raw/c"));
            assertTrue(n5.exists("raw"));
        }
    }

    @Test
    void cachingFactoriesKeepRepositoriesApartByUri() {
        try (Storage otherStorage = Storage.inMemory();
                Repository other = Repository.create(otherStorage);
                Session a = repo.writableSession("main");
                Session b = other.writableSession("main")) {
            N5FactoryWithCache factory = new N5FactoryWithCache();
            N5Writer first = factory.openWriter(
                    StorageFormat.ZARR3, new IcechunkKeyValueAccess(a), URI.create("icechunk://first"));
            N5Writer second = factory.openWriter(
                    StorageFormat.ZARR3, new IcechunkKeyValueAccess(b), URI.create("icechunk://second"));
            first.createGroup("only-in-first");
            assertTrue(a.store().exists("only-in-first/zarr.json"));
            assertFalse(second.exists("only-in-first"));
        }
    }

    @Test
    void n5UniverseGuessesZarrV3() {
        try (Session session = repo.writableSession("main")) {
            writer(session).createGroup("g");
            IcechunkKeyValueAccess kva = new IcechunkKeyValueAccess(session);
            assertFalse(kva.exists(".zarray"));
            assertFalse(kva.exists("g/.zgroup"));
            N5Reader n5 = new N5Factory().openReader(null, kva, URI.create(""));
            assertInstanceOf(ZarrV3KeyValueReader.class, n5);
            assertTrue(n5.exists("g"));
        }
    }

    @Test
    void readOnlySessionsRejectWrites() {
        try (Session session = repo.readonlySession(Version.branch("main"))) {
            IcechunkKeyValueAccess kva = new IcechunkKeyValueAccess(session);
            N5IOException e =
                    assertThrows(N5IOException.class, () -> kva.write("zarr.json", ReadData.from(new byte[0])));
            assertInstanceOf(IcechunkException.class, e.getCause());
        }
    }

    @Test
    void n5UniverseOpensWritersOverIt() {
        try (Session session = repo.writableSession("main")) {
            N5Writer n5 = new N5Factory()
                    .openWriter(StorageFormat.ZARR3, new IcechunkKeyValueAccess(session), URI.create(""));
            n5.createGroup("from-universe");
            assertTrue(n5.exists("from-universe"));
            assertTrue(session.store().exists("from-universe/zarr.json"));
        }
    }
}
