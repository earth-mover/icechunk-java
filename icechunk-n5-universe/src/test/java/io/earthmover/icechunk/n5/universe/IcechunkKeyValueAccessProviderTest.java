package io.earthmover.icechunk.n5.universe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.RepositoryOptions;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.SnapshotId;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Store;
import io.earthmover.icechunk.Version;
import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.ShortArrayDataBlock;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** n5-universe's N5Factory opening icechunk URLs of a local repository through the provider. */
class IcechunkKeyValueAccessProviderTest {
    @TempDir
    static Path tmp;

    static String repo;
    static String oddNames;
    static SnapshotId first;

    private static final String[] ODD_NAMES = {"a%20b", "a?b", "a#b", "t0:x", "0:x", "t_0:x"};

    @BeforeAll
    static void createRepository() {
        Path path = tmp.resolve("my repo");
        repo = path.toString();
        try (Storage storage = Storage.localFilesystem(path);
                Repository repository = Repository.create(storage)) {
            try (Session session = repository.writableSession("main")) {
                N5Writer n5 = writer(session);
                n5.createGroup("em data");
                DatasetAttributes attributes = n5.createDataset(
                        "em data/raw", new long[] {4, 4}, new int[] {4, 4}, DataType.UINT16, new RawCompression());
                n5.writeChunk("em data/raw", attributes, block(1));
                first = session.commit("first");
            }
            repository.createTag("v1", first);
            try (Session session = repository.writableSession("main")) {
                N5Writer n5 = writer(session);
                n5.writeChunk("em data/raw", n5.getDatasetAttributes("em data/raw"), block(2));
                session.commit("second");
            }
        }
        // Written through the store, since n5's writer reads a name such as t0:x as a URI scheme and drops it.
        Path odd = tmp.resolve("odd names");
        oddNames = odd.toString();
        try (Storage storage = Storage.localFilesystem(odd);
                Repository repository = Repository.create(storage);
                Session session = repository.writableSession("main")) {
            Store store = session.store();
            store.set("zarr.json", group("{}"));
            for (String name : ODD_NAMES) {
                store.set(name + "/zarr.json", group("{\"name\": \"" + name + "\"}"));
            }
            session.commit("odd names");
        }
    }

    private static ByteBuffer group(String attributes) {
        return ByteBuffer.wrap(("{\"zarr_format\": 3, \"node_type\": \"group\", \"attributes\": " + attributes + "}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static N5Writer writer(Session session) {
        return new ZarrV3KeyValueWriter(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
    }

    private static ShortArrayDataBlock block(int value) {
        short[] values = new short[16];
        Arrays.fill(values, (short) value);
        return new ShortArrayDataBlock(new int[] {4, 4}, new long[] {0, 0}, values);
    }

    private static short firstValue(N5Reader n5, String dataset) {
        return ((short[]) n5.readChunk(dataset, n5.getDatasetAttributes(dataset), 0, 0)
                        .getData())
                [0];
    }

    @Test
    void opensVersions() {
        N5Factory factory = new N5Factory();
        assertEquals(2, firstValue(factory.openReader(repo + "|icechunk://branch.main"), "em data/raw"));
        assertEquals(2, firstValue(factory.openReader(repo + "|icechunk:"), "em data/raw"));
        assertEquals(1, firstValue(factory.openReader(repo + "|icechunk://tag.v1"), "em data/raw"));
        assertEquals(1, firstValue(factory.openReader(repo + "|icechunk://" + first), "em data/raw"));
        assertEquals(2, firstValue(factory.openReader("file://" + repo + "|icechunk://branch.main"), "em data/raw"));
    }

    @Test
    void rootsTheReaderAtTheNode() {
        N5Reader n5 = new N5Factory().openReader(repo + "|icechunk://branch.main/em data");
        assertArrayEquals(new String[] {"raw"}, n5.list(""));
        assertTrue(n5.datasetExists("raw"));
        assertEquals(2, firstValue(n5, "raw"));
    }

    @Test
    void opensLocationsAResolverClaims() {
        N5Factory factory = new N5Factory();
        assertEquals(2, firstValue(factory.openReader("named:mine|icechunk://branch.main/em data"), "raw"));
        assertEquals(1, firstValue(factory.openReader("named:mine%7Cicechunk://tag.v1"), "em data/raw"));
        assertArrayEquals(
                new String[] {"em data"}, factory.openReader("named:mine").list(""));
        assertTrue(IcechunkKeyValueAccessProvider.repository("named:mine")
                .configJson()
                .contains("\"ideal_concurrent_request_size\":2097152"));
    }

    @Test
    void readsNodesWithUriSyntaxInTheirNames() {
        for (String url : new String[] {"named:odd|icechunk:", oddNames + "|icechunk:"}) {
            N5Reader n5 = new N5Factory().openReader(url);
            for (String name : ODD_NAMES) {
                assertTrue(n5.exists(name), url + " " + name);
                assertEquals(name, n5.getAttribute(name, "name", String.class));
            }
        }
    }

    @Test
    void reportsResolversThatFailedToLoadOrClaim() {
        N5Exception e =
                assertThrows(N5Exception.class, () -> IcechunkKeyValueAccessProvider.repository("nobody:org/repo"));
        assertTrue(e.getMessage().contains("no RepositoryResolver"));
        assertTrue(Arrays.stream(e.getSuppressed())
                .anyMatch(failure -> failure.getMessage().contains("MissingRepositoryResolver")));
        assertTrue(
                Arrays.stream(e.getSuppressed()).anyMatch(failure -> "broken resolver".equals(failure.getMessage())));
    }

    @Test
    void leavesTheQueryOutOfAnOpaqueUrlsLocation() {
        assertEquals(
                "named:mine",
                IcechunkUrl.parse(java.net.URI.create("named:mine%7Cicechunk:?x=1"))
                        .location());
    }

    @Test
    void opensWritersNever() {
        assertThrows(N5Exception.class, () -> new N5Factory().openWriter(repo + "|icechunk://branch.main"));
    }

    @Test
    void authorizesDeclaredContainersAnonymously() {
        String config = "{\"virtual_chunk_containers\": {"
                + "\"https://ftp.example.org/data/\": {\"store\": {\"http\": {}}},"
                + "\"s3://bucket/\": {\"store\": {\"s3_compatible\": {\"anonymous\": true}}},"
                + "\"file:///home/\": {\"store\": {\"local_file_system\": \"/home\"}}}}";
        assertEquals(
                Arrays.asList("https://ftp.example.org/data/", "s3://bucket/"),
                new ArrayList<>(
                        IcechunkKeyValueAccessProvider.anonymousAccess(config).keySet()));
        assertTrue(IcechunkKeyValueAccessProvider.anonymousAccess("{\"virtual_chunk_containers\": null}")
                .isEmpty());
    }

    @Test
    void layersRequestSizeAndRetriesOverTheStoredConfig() {
        Path path = tmp.resolve("configured");
        String stored = "{\"inline_chunk_threshold_bytes\": 7, \"virtual_chunk_containers\": {"
                + "\"https://ftp.example.org/data/\": {\"url_prefix\": \"https://ftp.example.org/data/\","
                + " \"store\": {\"http\": {}}}}}";
        try (Storage storage = Storage.localFilesystem(path)) {
            Repository.create(
                            storage,
                            RepositoryOptions.builder().configJson(stored).build())
                    .close();
        }

        JsonObject config = JsonParser.parseString(IcechunkKeyValueAccessProvider.repository(path.toString())
                        .configJson())
                .getAsJsonObject();
        assertEquals(
                2 << 20,
                config.getAsJsonObject("storage")
                        .getAsJsonObject("concurrency")
                        .get("ideal_concurrent_request_size")
                        .getAsLong());
        assertEquals(
                10,
                config.getAsJsonObject("storage")
                        .getAsJsonObject("retries")
                        .get("max_tries")
                        .getAsInt());
        assertEquals(7, config.get("inline_chunk_threshold_bytes").getAsInt());
        assertTrue(config.getAsJsonObject("virtual_chunk_containers").has("https://ftp.example.org/data/"));
    }

    @Test
    void readsTheRequestSizeFromItsProperty() {
        try {
            System.setProperty("icechunk.requestSize", "1048576");
            assertTrue(IcechunkKeyValueAccessProvider.storageConfig().contains("1048576"));
            System.setProperty("icechunk.requestSize", "0");
            assertThrows(N5Exception.class, IcechunkKeyValueAccessProvider::storageConfig);
        } finally {
            System.clearProperty("icechunk.requestSize");
        }
    }

    @Test
    void parsesUrls() {
        IcechunkUrl url = IcechunkUrl.parse("s3://bucket/repo%7Cicechunk://tag.v1/a b|zarr3:c/");
        assertEquals("s3://bucket/repo", url.location());
        assertEquals("a b/c", url.path());
        assertEquals("s3://bucket/repo|icechunk://tag.v1/a b/c", url.toString());
        assertEquals("", IcechunkUrl.parse("/data/repo.icechunk/").path());
        assertTrue(IcechunkUrl.claims("/data/repo.icechunk/"));
        assertTrue(IcechunkUrl.claims("gs://b/r%7Cicechunk:"));
        assertFalse(IcechunkUrl.claims("s3://bucket/data.zarr"));
        assertThrows(IllegalArgumentException.class, () -> IcechunkUrl.parse("s3://b/r|icechunk://branch."));
        assertThrows(IllegalArgumentException.class, () -> IcechunkUrl.parse("s3://b/r|zip:"));
    }

    @Test
    void parsesTheSpecificationsIcechunkExamples() {
        String repo = "file:///path/to/repo.zarr.icechunk/";
        for (String stage : new String[] {"icechunk:", "icechunk", "icechunk://branch.main/"}) {
            IcechunkUrl url = IcechunkUrl.parse(repo + "|" + stage);
            assertEquals(Version.branch("main"), url.version());
            assertEquals("", url.path());
            assertTrue(IcechunkUrl.claims(repo + "|" + stage));
        }
        for (String stage : new String[] {"icechunk:path/to/node/", "icechunk:/path/to/node/"}) {
            assertEquals("path/to/node", IcechunkUrl.parse(repo + "|" + stage).path());
        }
        IcechunkUrl branch = IcechunkUrl.parse(repo + "|icechunk://branch.mybranch/path/to/node/");
        assertEquals(Version.branch("mybranch"), branch.version());
        assertEquals("path/to/node", branch.path());
        assertEquals(
                Version.tag("a"), IcechunkUrl.parse(repo + "|icechunk://tag.a").version());
        assertEquals(
                Version.snapshot(SnapshotId.of("FWWFQGAW742XMX0F5MF0")),
                IcechunkUrl.parse(repo + "|icechunk://FWWFQGAW742XMX0F5MF0/path/to/node/")
                        .version());
        IcechunkUrl zarr = IcechunkUrl.parse(repo + "|icechunk://tag.v5/|zarr3:path/to/array/");
        assertEquals(Version.tag("v5"), zarr.version());
        assertEquals("path/to/array", zarr.path());
        assertFalse(IcechunkUrl.claims("s3://b/icechunks|zarr3:"));
    }

    @Test
    void rejectsTheEarlierDraftsVersionSyntax() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> IcechunkUrl.parse("s3://b/r|icechunk:@branch.dev/em"));
        assertTrue(e.getMessage().contains("icechunk://branch.dev/em"));
    }
}
