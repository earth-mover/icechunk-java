package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.RepositoryOptions;
import io.earthmover.icechunk.Storage;
import java.nio.file.Paths;

/** Opens {@code named:NAME} as the test repository, the way a hosted service maps names to repositories. */
public final class NamedRepositoryResolver implements RepositoryResolver {
    @Override
    public boolean claims(String location) {
        return location.startsWith("named:");
    }

    @Override
    public Repository open(String location, RepositoryOptions options) {
        try (Storage storage = Storage.localFilesystem(Paths.get(IcechunkKeyValueAccessProviderTest.repo))) {
            return Repository.open(storage, options);
        }
    }
}
