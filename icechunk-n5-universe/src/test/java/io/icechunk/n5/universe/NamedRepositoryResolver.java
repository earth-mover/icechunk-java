package io.icechunk.n5.universe;

import io.icechunk.Repository;
import io.icechunk.RepositoryOptions;
import io.icechunk.Storage;
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
