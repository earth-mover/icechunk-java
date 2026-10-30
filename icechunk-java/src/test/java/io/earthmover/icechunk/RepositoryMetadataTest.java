package io.earthmover.icechunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Metadata stored with the repository rather than with a snapshot. */
class RepositoryMetadataTest {
    @Test
    void metadataIsSetUpdatedAndReadBack() {
        try (Storage storage = Storage.inMemory()) {
            try (Repository repo = Repository.create(storage)) {
                assertEquals(Collections.emptyMap(), repo.getMetadata());

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("title", "ERA5 subset");
                metadata.put("version", 2);
                metadata.put("tags", Arrays.asList("climate", "reanalysis"));
                repo.setMetadata(metadata);

                Map<String, Object> merged = repo.updateMetadata(Collections.singletonMap("version", 3));
                assertEquals("ERA5 subset", merged.get("title"));
                assertEquals(3L, merged.get("version"));
            }
            // Stored with the repository, so a new Repository sees it.
            try (Repository repo = Repository.open(storage)) {
                Map<String, Object> expected = new LinkedHashMap<>();
                expected.put("tags", Arrays.asList("climate", "reanalysis"));
                expected.put("title", "ERA5 subset");
                expected.put("version", 3L);
                assertEquals(expected, repo.getMetadata());

                repo.setMetadata(Collections.singletonMap("only", true));
                assertEquals(Collections.singletonMap("only", true), repo.getMetadata());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repo.setMetadata(Collections.singletonMap("bad", new Object())));
            }
        }
    }

    @Test
    void version1RepositoriesHaveNoMetadata() {
        try (Storage storage = Storage.inMemory();
                Repository repo = Repository.create(
                        storage, RepositoryOptions.builder().specVersion(1).build())) {
            assertThrows(IcechunkException.class, repo::getMetadata);
        }
    }
}
