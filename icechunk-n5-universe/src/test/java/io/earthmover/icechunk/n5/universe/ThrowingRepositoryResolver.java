package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.RepositoryOptions;

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
