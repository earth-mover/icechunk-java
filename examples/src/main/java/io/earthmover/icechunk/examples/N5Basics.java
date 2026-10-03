package io.earthmover.icechunk.examples;

import com.google.gson.GsonBuilder;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.SnapshotId;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.ShortArrayDataBlock;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueWriter;

/**
 * N5 over icechunk: write a dataset with n5-zarr, commit it, read it back from the branch and from an older snapshot,
 * and keep one writer across commits.
 */
public final class N5Basics {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("icechunk-n5");

        // --8<-- [start:repository]
        try (Storage storage = Storage.localFilesystem(dir);
                Repository repo = Repository.create(storage)) {
            // --8<-- [end:repository]

            SnapshotId first;
            // --8<-- [start:write]
            try (Session session = repo.writableSession("main")) {
                N5Writer n5 =
                        new ZarrV3KeyValueWriter(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
                n5.createGroup("em");
                n5.setAttribute("em", "units", "nm");
                DatasetAttributes attributes = n5.createDataset(
                        "em/raw", new long[] {64, 64}, new int[] {32, 32}, DataType.UINT16, new GzipCompression());
                n5.writeChunk(
                        "em/raw", attributes, new ShortArrayDataBlock(new int[] {32, 32}, new long[] {0, 0}, fill(1)));
                first = session.commit("Add em/raw");
            }
            // --8<-- [end:write]
            System.out.println("first commit:  " + first);

            try (Session session = repo.writableSession("main")) {
                N5Writer n5 =
                        new ZarrV3KeyValueWriter(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
                DatasetAttributes attributes = n5.getDatasetAttributes("em/raw");
                n5.writeChunk(
                        "em/raw", attributes, new ShortArrayDataBlock(new int[] {32, 32}, new long[] {0, 0}, fill(2)));
                System.out.println("second commit: " + session.commit("Overwrite chunk (0, 0)"));
            }

            // --8<-- [start:read]
            try (Session latest = repo.readonlySession(Version.branch("main"));
                    Session earlier = repo.readonlySession(Version.snapshot(first))) {
                N5Reader now =
                        new ZarrV3KeyValueReader(new IcechunkKeyValueAccess(latest), "", new GsonBuilder(), false);
                N5Reader then =
                        new ZarrV3KeyValueReader(new IcechunkKeyValueAccess(earlier), "", new GsonBuilder(), false);
                DatasetAttributes attributes = now.getDatasetAttributes("em/raw");
                short[] current =
                        (short[]) now.readChunk("em/raw", attributes, 0, 0).getData();
                short[] original =
                        (short[]) then.readChunk("em/raw", attributes, 0, 0).getData();
                // --8<-- [end:read]
                System.out.println("units:   " + now.getAttribute("em", "units", String.class));
                System.out.println("latest:  chunk (0, 0) starts with " + current[0]);
                System.out.println("earlier: chunk (0, 0) starts with " + original[0]);
            }

            // --8<-- [start:universe]
            try (Session session = repo.readonlySession(Version.branch("main"))) {
                N5Reader n5 = new N5Factory()
                        .openReader(StorageFormat.ZARR3, new IcechunkKeyValueAccess(session), URI.create(""));
                // --8<-- [end:universe]
                System.out.println("n5-universe: em/raw is a dataset: " + n5.datasetExists("em/raw"));
            }

            // --8<-- [start:swap]
            AtomicReference<Session> current = new AtomicReference<>(repo.writableSession("main"));
            N5Writer n5 = new ZarrV3KeyValueWriter(
                    new IcechunkKeyValueAccess(() -> current.get().store()), "", new GsonBuilder(), false);
            DatasetAttributes attributes = n5.getDatasetAttributes("em/raw");
            for (int step = 0; step < 3; step++) {
                n5.writeChunk(
                        "em/raw",
                        attributes,
                        new ShortArrayDataBlock(new int[] {32, 32}, new long[] {1, 1}, fill(10 + step)));
                current.get().commit("Edit " + step);
                current.getAndSet(repo.writableSession("main")).close();
            }
            current.get().close();
            // --8<-- [end:swap]

            System.out.println("history:");
            repo.ancestry(Version.branch("main"))
                    .forEach(snapshot -> System.out.println("  " + snapshot.id() + "  " + snapshot.message()));
        }
    }

    private static short[] fill(int value) {
        short[] values = new short[32 * 32];
        Arrays.fill(values, (short) value);
        return values;
    }
}
