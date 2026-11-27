/**
 * Java bindings for icechunk, the transactional storage engine for Zarr.
 *
 * <p>Open a {@link io.icechunk.Repository} on a {@link io.icechunk.Storage}, then read and write
 * Zarr keys through a {@link io.icechunk.Session} and its {@link io.icechunk.Store}. Writes
 * become a new snapshot when the session commits. Classes that hold a native object, such as {@code Storage},
 * {@code Repository}, {@code Session} and {@code Store}, should be closed when done.
 */
package io.icechunk;
