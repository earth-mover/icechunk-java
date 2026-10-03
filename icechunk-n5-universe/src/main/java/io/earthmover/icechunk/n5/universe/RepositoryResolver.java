package io.earthmover.icechunk.n5.universe;

import io.earthmover.icechunk.Repository;
import io.earthmover.icechunk.RepositoryOptions;

/**
 * Opens repositories at locations that are not storage URLs, such as the repository names of a service that hosts
 * icechunk repositories. {@link IcechunkKeyValueAccessProvider} finds implementations through
 * {@link java.util.ServiceLoader}, so registering one in {@code META-INF/services} makes URLs with its locations open
 * through n5-universe, such as {@code service:org/repo|icechunk://branch.main/em/raw}.
 *
 * <p>The location is the part of the URL before the first {@code |}, without trailing slashes. A location a resolver
 * claims can also stand alone as a URL, without an {@code icechunk:} stage; it then names the main branch's root.
 */
public interface RepositoryResolver {
    /** Returns true if this resolver opens the repository at {@code location}. */
    boolean claims(String location);

    /**
     * Opens the repository at {@code location}, with {@code options}' configuration layered over the repository's own.
     * The options authorize no virtual chunk containers: the resolver supplies whatever credentials the repository's
     * virtual chunks need.
     */
    Repository open(String location, RepositoryOptions options);
}
