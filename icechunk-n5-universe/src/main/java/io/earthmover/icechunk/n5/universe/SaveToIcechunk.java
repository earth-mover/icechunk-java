package io.earthmover.icechunk.n5.universe;

import ij.ImagePlus;
import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.Session;
import io.earthmover.icechunk.SnapshotId;
import io.earthmover.icechunk.Storage;
import io.earthmover.icechunk.n5.IcechunkKeyValueAccess;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.ij.N5Importer;
import org.janelia.saalfeldlab.n5.ij.N5ScalePyramidExporter;
import org.janelia.saalfeldlab.n5.universe.KeyValueAccessProvider;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.UIService;

/**
 * Saves an image into an icechunk repository as an OME-Zarr image, and commits it to a branch.
 *
 * <p>n5-ij's {@link N5ScalePyramidExporter} writes the image, so the metadata, pyramid and compression are those of
 * Fiji's own OME-Zarr export. The exporter opens its container from a URL with {@link N5Factory}, so this command
 * registers a {@link KeyValueAccessProvider} for one URL made up for the save, whose store is a writable session on
 * the branch. The session is committed once the exporter returns; if anything fails, nothing is committed.
 *
 * <p>A branch that does not exist is created at the tip of {@code main}, and deleted again if the save fails. A
 * repository is created only at a local path, and only when asked.
 */
@Plugin(
        type = Command.class,
        menuPath = "File>Save As>icechunk...",
        label = "Save to icechunk",
        description = "Save the current image into an icechunk repository as OME-Zarr, and commit it.")
// Fiji's Swing dialogs still size text fields from the deprecated Parameter.columns.
@SuppressWarnings("deprecation")
public final class SaveToIcechunk implements Command {
    private static final String SCHEME = "icechunk-save";

    @Parameter
    LogService log;

    @Parameter
    StatusService status;

    @Parameter
    UIService ui;

    @Parameter(label = "Image")
    ImagePlus image;

    @Parameter(
            label = "Repository",
            columns = 40,
            description = "The repository's location: a local path, s3://bucket/prefix, gs://bucket/prefix, an http(s)"
                    + " URL, or a name a repository resolver opens, such as al:org/repo.")
    String location;

    @Parameter(label = "Branch", description = "Created at the tip of main if it does not exist.")
    String branch = "main";

    @Parameter(
            label = "Path in repository",
            columns = 40,
            description = "The group to write the image to, such as cells3d/labels/nuclei. Missing parent groups are"
                    + " created.")
    String path;

    @Parameter(label = "Commit message", required = false, persist = false, columns = 40)
    String message;

    @Parameter(
            label = "Chunk size",
            description = "Comma separated, in the order X,Y,Z,C,T, including axes of size 1. Missing Y and Z values"
                    + " follow the voxel size so that chunks are near cubic, a missing C is 1, and a missing T repeats"
                    + " the last value.")
    String chunkSize = "64";

    @Parameter(
            label = "Compression",
            choices = {
                N5ScalePyramidExporter.GZIP_COMPRESSION,
                N5ScalePyramidExporter.ZSTD_COMPRESSION,
                N5ScalePyramidExporter.BLOSC_COMPRESSION,
                N5ScalePyramidExporter.RAW_COMPRESSION
            })
    String compression = N5ScalePyramidExporter.GZIP_COMPRESSION;

    @Parameter(label = "Create pyramid")
    boolean pyramid = true;

    @Parameter(
            label = "Downsampling method",
            choices = {N5ScalePyramidExporter.DOWN_SAMPLE, N5ScalePyramidExporter.DOWN_AVERAGE})
    String downsampling = N5ScalePyramidExporter.DOWN_SAMPLE;

    @Parameter(
            label = "Overwrite",
            description =
                    "Delete what is at the path, and everything inside it, before writing. Earlier snapshots keep it.",
            persist = false)
    boolean overwrite = false;

    @Parameter(
            label = "Create repository if missing",
            description = "Create a new repository at a local path that holds none. Repositories elsewhere are never"
                    + " created.",
            persist = false)
    boolean create = false;

    @Override
    public void run() {
        try {
            SnapshotId snapshot = save();
            log.info("Saved " + image.getTitle() + " to " + location + " at " + path + " on branch " + branch
                    + " as snapshot " + snapshot);
            status.showStatus("Committed snapshot " + snapshot + " to " + branch);
        } catch (IOException | ExecutionException | RuntimeException e) {
            fail(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private void fail(Exception e) {
        String reason =
                "Could not save " + image.getTitle() + " to " + location + "; nothing was committed: " + e.getMessage();
        log.error(reason, e);
        ui.showDialog(reason, "Save to icechunk", MessageType.ERROR_MESSAGE);
    }

    /** Writes the image into a session on the branch and commits it. */
    SnapshotId save() throws IOException, InterruptedException, ExecutionException {
        String given = path == null ? "" : path;
        checkPath(given);
        String node = N5URI.normalizeGroupPath(given);
        if (node.isEmpty()) {
            throw new IllegalArgumentException("give a path inside the repository to save the image at");
        }
        if (location.contains("|") || location.toLowerCase(Locale.ROOT).contains("%7c")) {
            throw new IllegalArgumentException(
                    "give the repository's location without an icechunk: stage; the branch and path have their own"
                            + " fields: " + location);
        }
        checkChunkSize();
        Repository repository = repository(IcechunkUrl.parse(location).location());
        boolean createdBranch = !repository.listBranches().contains(branch);
        if (createdBranch) {
            repository.createBranch(branch, repository.lookupBranch("main"));
        }
        try (Session session = repository.writableSession(branch)) {
            export(new UrlKeyValueAccess(new IcechunkKeyValueAccess(session), ""), node);
            return session.commit(
                    message == null || message.trim().isEmpty() ? "Save " + image.getTitle() + " to " + node : message);
        } catch (Throwable t) {
            if (createdBranch) {
                try {
                    repository.deleteBranch(branch);
                } catch (RuntimeException e) {
                    t.addSuppressed(e);
                }
            }
            throw t;
        }
    }

    /**
     * Refuses a path n5-universe cannot write OME-Zarr metadata for: it reads the image's path as a URI, which fails on
     * a space and other characters a URI path cannot hold, and misreads {@code ?}, {@code #}, {@code %} and {@code :}.
     * Also refuses {@code .} and {@code ..} segments, which N5 would resolve to some other node or fail on.
     */
    private static void checkPath(String given) {
        for (String segment : given.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("the path cannot contain '" + segment
                        + "' as a name; give the full path from the root: " + given);
            }
        }
        for (int i = 0; i < given.length(); i++) {
            char c = given.charAt(i);
            boolean allowed = (c < 128 && (Character.isLetterOrDigit(c) || "-._~!$&'()*+,;=@/".indexOf(c) >= 0))
                    || (c >= 128 && !Character.isSpaceChar(c) && !Character.isISOControl(c));
            if (!allowed) {
                throw new IllegalArgumentException(
                        "the path cannot contain '" + c + "'" + (c == ' ' ? " (a space)" : "")
                                + ", since n5-universe cannot write OME-Zarr metadata for it; use names like labels/stardist: "
                                + given);
            }
        }
    }

    /**
     * Refuses a chunk size the exporter would not use as given: it skips values that are not positive numbers and
     * values beyond its five axes X,Y,Z,C,T, rather than failing.
     */
    private void checkChunkSize() {
        for (String level : chunkSize.trim().replaceFirst(";+$", "").split(";", -1)) {
            String[] sizes = level.split(",", -1);
            for (String size : sizes) {
                if (!size.trim().matches("0*[1-9][0-9]{0,8}")) {
                    throw new IllegalArgumentException(
                            "give the chunk size as positive whole numbers separated by commas, such as 64 or"
                                    + " 64,64,16: " + chunkSize);
                }
            }
            if (sizes.length > 5) {
                throw new IllegalArgumentException("the chunk size " + chunkSize + " has " + sizes.length
                        + " values, but the image is saved with five axes, X,Y,Z,C,T");
            }
        }
    }

    /** Opens the repository, first creating it if it is missing, asked to be, and at a local path. */
    private Repository repository(String repositoryLocation) {
        if (IcechunkUrl.hasScheme(repositoryLocation)) {
            try {
                return IcechunkKeyValueAccessProvider.repository(repositoryLocation);
            } catch (N5Exception e) {
                if (!create) {
                    throw e;
                }
                throw new N5Exception(
                        "cannot open " + location + ", and only repositories at local paths are created: "
                                + e.getMessage(),
                        e);
            }
        }
        try (Storage storage = Storage.localFilesystem(Paths.get(repositoryLocation))) {
            if (!Repository.exists(storage)) {
                if (!create) {
                    throw new N5Exception(
                            "no repository at " + location + "; check Create repository if missing to create one");
                }
                Repository.create(storage).close();
            }
        }
        return IcechunkKeyValueAccessProvider.repository(repositoryLocation);
    }

    private void export(KeyValueAccess store, String node)
            throws IOException, InterruptedException, ExecutionException {
        URI container = URI.create(SCHEME + "://" + UUID.randomUUID() + "/");
        // Uncached, so that the check after the export sees what the exporter wrote.
        N5Writer n5 = new N5Factory()
                .options(options -> options.cacheAttributes(false))
                .openWriter(StorageFormat.ZARR3, store, container);
        clear(n5, node);

        KeyValueAccessProvider provider = new KeyValueAccessProvider() {
            @Override
            public boolean test(URI uri) {
                return SCHEME.equals(uri.getScheme())
                        && container.getAuthority().equals(uri.getAuthority());
            }

            @Override
            public KeyValueAccess apply(URI uri, N5Factory factory, boolean readOnly) {
                return store;
            }
        };
        N5ScalePyramidExporter exporter = new N5ScalePyramidExporter(
                image,
                "zarr3:" + container,
                node,
                N5ScalePyramidExporter.AUTO_FORMAT,
                chunkSize,
                pyramid,
                downsampling,
                N5Importer.MetadataOmeZarrKey,
                compression);
        // Unset, the exporter asks in a dialog whether to overwrite. clear has emptied the path, so it finds nothing
        // to.
        exporter.setOverwrite(false);
        KeyValueAccessProvider.register(provider);
        try {
            exporter.processMultiscale();
        } finally {
            KeyValueAccessProvider.unregister(provider);
        }
        // The exporter returns without writing, rather than throwing, when it declines to write.
        if (!n5.exists(node)) {
            throw new N5Exception("the exporter wrote nothing at " + node);
        }
    }

    /**
     * Refuses to write below an array, and, unless overwrite is set, where a group or array exists. With overwrite set,
     * deletes the group or array at {@code node} in the session.
     */
    private void clear(N5Writer n5, String node) {
        String array = arrayAbove(n5, node);
        if (array != null) {
            throw new N5Exception((array.isEmpty() ? "the repository root" : array) + " is an array on branch " + branch
                    + "; an image cannot be saved inside it");
        }
        if (!n5.exists(node)) {
            return;
        }
        if (!overwrite) {
            throw new N5Exception(node + " already exists on branch " + branch + "; check Overwrite to replace it");
        }
        n5.remove(node);
    }

    /** The array at the root or a parent of {@code node}, or null if there is none. */
    private static String arrayAbove(N5Writer n5, String node) {
        for (int slash = 0; slash >= 0; slash = node.indexOf('/', slash + 1)) {
            String parent = node.substring(0, slash);
            if (n5.datasetExists(parent)) {
                return parent;
            }
        }
        return null;
    }
}
