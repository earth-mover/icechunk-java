package io.earthmover.icechunk.examples;

import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.DataType;
import dev.zarr.zarrjava.v3.Group;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.SnapshotId;
import io.earthmover.icechunk.SnapshotInfo;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.Version;
import io.earthmover.icechunk.zarr.IcechunkZarrStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** The icechunk quickstart: create a repository, write and commit twice, then read the first version back. */
public final class Quickstart {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("icechunk-quickstart");

        try (Storage storage = Storage.localFilesystem(dir);
                Repository repo = Repository.create(storage)) {

            SnapshotId first;
            try (Session session = repo.writableSession("main")) {
                IcechunkZarrStore store = new IcechunkZarrStore(session);
                Group.create(store.resolve());
                Array array = Array.create(
                        store.resolve("my_array"),
                        Array.metadataBuilder()
                                .withShape(10)
                                .withDataType(DataType.INT32)
                                .withChunkShape(5)
                                .withFillValue(0)
                                .build());
                array.write(ints(10, 1));
                first = session.commit("first commit");
                System.out.println("first commit: " + first);
            }

            try (Session session = repo.writableSession("main")) {
                Array array = Array.open(new IcechunkZarrStore(session).resolve("my_array"));
                array.write(new long[] {0}, ints(5, 2));
                session.commit("overwrite some values");
            }

            for (SnapshotInfo snapshot : repo.ancestry(Version.branch("main"))) {
                System.out.println(snapshot.id() + "  " + snapshot.writtenAt() + "  " + snapshot.message());
            }

            try (Session latest = repo.readonlySession(Version.branch("main"));
                    Session earlier = repo.readonlySession(Version.snapshot(first))) {
                System.out.println("latest:  " + Arrays.toString(read(latest)));
                System.out.println("earlier: " + Arrays.toString(read(earlier)));
            }
        }
    }

    private static int[] read(Session session) throws Exception {
        Array array = Array.open(new IcechunkZarrStore(session).resolve("my_array"));
        return (int[]) array.read().get1DJavaArray(ucar.ma2.DataType.INT);
    }

    private static ucar.ma2.Array ints(int length, int value) {
        int[] values = new int[length];
        Arrays.fill(values, value);
        return ucar.ma2.Array.factory(ucar.ma2.DataType.INT, new int[] {length}, values);
    }
}
