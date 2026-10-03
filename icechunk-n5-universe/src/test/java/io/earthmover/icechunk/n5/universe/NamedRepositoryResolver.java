package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.RepositoryOptions;
import io.earthmover.icechunk.Storage;
import java.nio.file.Paths;

/**
 * Opens {@code named:odd} as the test repository with unusual node names, and any other {@code named:NAME} as the main
 * test repository, the way a hosted service maps names to repositories.
 */
public final class NamedRepositoryResolver implements RepositoryResolver {
    @Override
    public boolean claims(String location) {
        return location.startsWith("named:");
    }

    @Override
    public Repository open(String location, RepositoryOptions options) {
        String path = location.equals("named:odd")
                ? IcechunkKeyValueAccessProviderTest.oddNames
                : IcechunkKeyValueAccessProviderTest.repo;
        try (Storage storage = Storage.localFilesystem(Paths.get(path))) {
            return Repository.open(storage, options);
        }
    }
}
