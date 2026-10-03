package io.earthmover.icechunk.n5.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import ij.ImagePlus;
import ij.ImageStack;
import ij.VirtualStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.SnapshotId;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Saving an image into a local repository with {@link SaveToIcechunk}, read back through N5Factory. */
class SaveToIcechunkTest {
    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;
    private static final int DEPTH = 4;

    @TempDir
    Path tmp;

    /** A repository whose main branch has a group cells3d with an attribute. */
    private String repository() {
        Path path = tmp.resolve("repo");
        try (Storage storage = Storage.localFilesystem(path);
                Repository repository = Repository.create(storage);
                Session session = repository.writableSession("main")) {
            N5Writer n5 = new ZarrV3KeyValueWriter(new IcechunkKeyValueAccess(session), "", new GsonBuilder(), false);
            n5.createGroup("cells3d");
            n5.setAttribute("cells3d", "kept", "yes");
            session.commit("cells3d");
        }
        return path.toString();
    }

    /** A 3D uint16 image whose value at x, y, z is x + 10 y + 100 z + offset. */
    private static ImagePlus labels(int offset) {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int z = 0; z < DEPTH; z++) {
            short[] pixels = new short[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    pixels[x + WIDTH * y] = (short) value(x, y, z, offset);
                }
            }
            stack.addSlice(new ShortProcessor(WIDTH, HEIGHT, pixels, null));
        }
        ImagePlus image = new ImagePlus("labels", stack);
        image.setDimensions(1, DEPTH, 1);
        return image;
    }

    private static int value(long x, long y, long z, int offset) {
        return (int) (x + 10 * y + 100 * z + offset);
    }

    private static SaveToIcechunk command(String repo, String branch, String path, ImagePlus image) {
        SaveToIcechunk command = new SaveToIcechunk();
        command.image = image;
        command.location = repo;
        command.branch = branch;
        command.path = path;
        command.message = "save labels";
        command.chunkSize = "4";
        return command;
    }

    private static SnapshotId tip(String repo, String branch) {
        return IcechunkKeyValueAccessProvider.repository(repo).lookupBranch(branch);
    }

    private static void assertValues(N5Reader n5, String dataset, int offset) {
        RandomAccessibleInterval<UnsignedShortType> img = N5Utils.open(n5, dataset);
        assertEquals(WIDTH, img.dimension(0));
        assertEquals(HEIGHT, img.dimension(1));
        assertEquals(DEPTH, img.dimension(2));
        Cursor<UnsignedShortType> cursor = Views.flatIterable(img).localizingCursor();
        while (cursor.hasNext()) {
            int actual = cursor.next().get();
            assertEquals(
                    value(cursor.getLongPosition(0), cursor.getLongPosition(1), cursor.getLongPosition(2), offset),
                    actual);
        }
    }

    @Test
    void savesAnOmeZarrImageAndCommitsIt() throws Exception {
        String repo = repository();
        SnapshotId snapshot =
                command(repo, "main", "cells3d/labels/nuclei", labels(0)).save();

        assertEquals(snapshot, tip(repo, "main"));
        assertEquals(
                "save labels",
                IcechunkKeyValueAccessProvider.repository(repo)
                        .lookupSnapshot(snapshot)
                        .message());
        N5Reader n5 = new N5Factory().openReader(repo + "|icechunk://branch.main/cells3d/labels/nuclei");
        assertEquals("0.5", n5.getAttribute("", "ome/version", String.class));
        assertEquals("s0", n5.getAttribute("", "ome/multiscales[0]/datasets[0]/path", String.class));
        String[] axes = {"t", "c", "z", "y", "x"};
        for (int i = 0; i < axes.length; i++) {
            assertEquals(axes[i], n5.getAttribute("", "ome/multiscales[0]/axes[" + i + "]/name", String.class));
        }
        assertTrue(n5.datasetExists("s1"), "pyramid level s1");
        assertValues(n5, "s0", 0);
        assertEquals(
                "yes", new N5Factory().openReader(repo + "|icechunk:").getAttribute("cells3d", "kept", String.class));
    }

    @Test
    void createsAMissingBranchAtMainsTip() throws Exception {
        String repo = repository();
        SnapshotId main = tip(repo, "main");
        SnapshotId snapshot =
                command(repo, "stardist", "cells3d/labels/nuclei", labels(0)).save();

        assertEquals(snapshot, tip(repo, "stardist"));
        assertEquals(main, tip(repo, "main"));
        assertEquals(
                main,
                IcechunkKeyValueAccessProvider.repository(repo)
                        .lookupSnapshot(snapshot)
                        .parentId()
                        .orElse(null));
        assertValues(new N5Factory().openReader(repo + "|icechunk://branch.stardist/cells3d/labels/nuclei"), "s0", 0);
        assertFalse(new N5Factory().openReader(repo + "|icechunk:").exists("cells3d/labels"));
    }

    @Test
    void commitsNothingWhenTheSaveFails() {
        String repo = repository();
        SnapshotId main = tip(repo, "main");
        for (String branch : new String[] {"main", "stardist"}) {
            // The exporter reads every slice for the metadata, then again slice by slice as it writes chunks, so with
            // one chunk per two slices the first chunks are in the session when the last slice fails.
            SaveToIcechunk command = command(repo, branch, "cells3d/labels/nuclei", lastSliceReadable(1));
            command.chunkSize = "2";
            Throwable cause = assertThrows(RuntimeException.class, command::save);
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertEquals("unreadable slice " + DEPTH, cause.getMessage());
        }
        assertEquals(main, tip(repo, "main"));
        assertFalse(new N5Factory().openReader(repo + "|icechunk:").exists("cells3d/labels"));
        assertFalse(
                IcechunkKeyValueAccessProvider.repository(repo).listBranches().contains("stardist"));
    }

    /** The labels image, whose last slice throws when read more than {@code reads} times. */
    private static ImagePlus lastSliceReadable(int reads) {
        ImageStack labels = labels(0).getStack();
        AtomicInteger lastSliceReads = new AtomicInteger();
        VirtualStack stack = new VirtualStack(WIDTH, HEIGHT, null, null) {
            @Override
            public ImageProcessor getProcessor(int n) {
                if (n == DEPTH && lastSliceReads.incrementAndGet() > reads) {
                    throw new IllegalStateException("unreadable slice " + n);
                }
                return labels.getProcessor(n);
            }
        };
        for (int z = 0; z < DEPTH; z++) {
            stack.addSlice("z" + z);
        }
        return new ImagePlus("labels", stack);
    }

    @Test
    void overwritesOnlyWhenAsked() throws Exception {
        String repo = repository();
        command(repo, "main", "cells3d/labels/nuclei", labels(0)).save();
        SnapshotId first = tip(repo, "main");

        N5Exception refused = assertThrows(
                N5Exception.class,
                () -> command(repo, "main", "cells3d/labels/nuclei", labels(1)).save());
        assertTrue(refused.getMessage().contains("cells3d/labels/nuclei already exists"), refused.getMessage());
        N5Exception below = assertThrows(
                N5Exception.class,
                () -> command(repo, "main", "cells3d/labels/nuclei/s0/inner", labels(1))
                        .save());
        assertTrue(below.getMessage().contains("cells3d/labels/nuclei/s0 already exists"), below.getMessage());
        assertEquals(first, tip(repo, "main"));

        SaveToIcechunk overwrite = command(repo, "main", "cells3d/labels/nuclei", labels(1));
        overwrite.overwrite = true;
        overwrite.save();
        assertValues(new N5Factory().openReader(repo + "|icechunk://branch.main/cells3d/labels/nuclei"), "s0", 1);
        assertValues(new N5Factory().openReader(repo + "|icechunk://" + first + "/cells3d/labels/nuclei"), "s0", 0);
    }
}
