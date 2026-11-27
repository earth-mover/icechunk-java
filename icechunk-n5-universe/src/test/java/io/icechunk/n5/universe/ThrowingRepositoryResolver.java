package io.icechunk.n5.universe;

import io.icechunk.Repository;
import io.icechunk.RepositoryOptions;

/** Throws from {@code claims}, as a broken third-party resolver might; other locations must still open. */
public final class ThrowingRepositoryResolver implements RepositoryResolver {
    @Override
    public boolean claims(String location) {
        throw new IllegalStateException("broken resolver");
    }

    @Override
    public Repository open(String location, RepositoryOptions options) {
        throw new IllegalStateException("broken resolver");
    }
}
