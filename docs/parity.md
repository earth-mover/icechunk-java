# Parity with icechunk-python

How the Java API compares with icechunk-python 2.x, feature by feature. "PR" entries are open pull requests.
Priorities: **P1** blocks common workflows, **P2** matters for some users, **P3** is rarely needed from Java.

| Feature | icechunk-python | icechunk-java | Gap priority |
|---|---|---|---|
| **Storage** | | | |
| In-memory, local filesystem | `in_memory_storage`, `local_filesystem_storage` | `Storage.inMemory`, `Storage.localFilesystem` | — |
| S3 and S3-compatible (endpoint, path style, HTTP) | `s3_storage` | `Storage.s3(S3Options)` | — |
| Tigris, R2 helpers | `tigris_storage`, `r2_storage` | through `S3Options.endpointUrl` | P3 |
| GCS | `gcs_storage` | `Storage.gcs(GcsOptions)` | — |
| Azure Blob | `azure_storage` | `Storage.azure(AzureOptions)` | — |
| HTTP (read-only) | `http_storage` | `Storage.http` | — |
| **Credentials** | | | |
| Static, environment, anonymous (S3, GCS, Azure) | yes | yes | — |
| Refreshable S3 / GCS credentials | `s3_refreshable_credentials`, `gcs_refreshable_credentials` | `S3Credentials.refreshable`, `GcsCredentials.refreshable` ([#10](https://github.com/earth-mover/icechunk-java/pull/10)) | P1, PR |
| Refreshable credentials for virtual chunk containers | yes | no | P2 |
| JVM proxy settings and trust store | n/a | no; the native client ignores them | P2 |
| **Repository** | | | |
| create / open / open_or_create / exists | yes | yes | — |
| Spec version, check clean root | yes | `RepositoryOptions.specVersion`, `checkCleanRoot` | — |
| Read the configuration | `repo.config` (typed) | `configJson()` (JSON) | P3 |
| Set the configuration with types | `RepositoryConfig(...)` | `RepositoryConfig.builder()` ([#12](https://github.com/earth-mover/icechunk-java/pull/12)); `configJson` before | P1, PR |
| Save the configuration | `save_config` | `saveConfig()` ([#12](https://github.com/earth-mover/icechunk-java/pull/12)) | P1, PR |
| fetch_config, reopen | yes | no | P3 |
| Repository metadata | `get_metadata`, `set_metadata`, `update_metadata` | `getMetadata`, `setMetadata`, `updateMetadata` ([#14](https://github.com/earth-mover/icechunk-java/pull/14)) | P1, PR |
| Default commit metadata | `set_default_commit_metadata` | no; pass `CommitOptions` | P3 |
| Feature flags, ops log, status | yes | no | P3 |
| **Sessions** | | | |
| Read-only on branch, tag, snapshot, as-of time | yes | `readonlySession(Version.*)` | — |
| Writable | `writable_session` | `writableSession` | — |
| Rearrange (move / rename nodes) | `rearrange_session`, `session.move` | `rearrangeSession`, `Session.move` ([#11](https://github.com/earth-mover/icechunk-java/pull/11)) | P1, PR |
| status, discard_changes, has_uncommitted_changes | yes | yes | — |
| fork / merge for distributed writes | `fork`, `merge` | no; share one session across threads | P3 |
| Async API | `async` methods | blocking calls only | P2 |
| **Commits** | | | |
| Message, metadata, allow_empty | yes | `commit(message, CommitOptions)` | — |
| Amend | `amend` | `Session.amend` ([#11](https://github.com/earth-mover/icechunk-java/pull/11)) | P2, PR |
| Flush (snapshot without moving the branch) | `flush` | `Session.flush` ([#11](https://github.com/earth-mover/icechunk-java/pull/11)) | P2, PR |
| Rebase with conflict solvers | `rebase(ConflictDetector / BasicConflictSolver)` | `Session.rebase(ConflictSolver)` ([#11](https://github.com/earth-mover/icechunk-java/pull/11)) | P1, PR |
| Commit with automatic rebase | `rebase_with`, `rebase_tries` | `CommitOptions.rebaseWith`, `rebaseTries` ([#11](https://github.com/earth-mover/icechunk-java/pull/11)) | P1, PR |
| Conflict details | `RebaseFailedError.conflicts` | `RebaseFailedException.conflicts()` ([#11](https://github.com/earth-mover/icechunk-java/pull/11)) | P1, PR |
| **Branches and tags** | | | |
| create / delete / list / lookup branches | yes | yes | — |
| reset_branch, with expected snapshot | yes | `resetBranch(branch, snapshot[, expected])` | — |
| create / delete / list / lookup tags | yes | yes | — |
| **History** | | | |
| ancestry, lookup_snapshot, resolve version | yes | yes | — |
| diff, session status | yes | `diff`, `status` | — |
| **Virtual chunks** | | | |
| Container configuration | `VirtualChunkContainer` in config | `VirtualChunkContainer` ([#12](https://github.com/earth-mover/icechunk-java/pull/12)); `configJson` before | P1, PR |
| Container credentials | `authorize_virtual_chunk_access` | `RepositoryOptions.authorizeVirtualChunkAccess` | — |
| Read virtual chunks | yes | yes | — |
| Write virtual refs | `set_virtual_ref`, `set_virtual_refs` | `Store.setVirtualRef`, `setVirtualRefs` ([#13](https://github.com/earth-mover/icechunk-java/pull/13)) | P2, PR |
| List virtual chunk locations | `all_virtual_chunk_locations` | no | P3 |
| **Maintenance** | | | |
| Expire snapshots | `expire_snapshots` | `expireSnapshots` | — |
| Garbage collection | `garbage_collect` | `garbageCollect` | — |
| Rewrite manifests | `rewrite_manifests` | no | P2 |
| Chunk storage statistics | `total_chunks_storage` | no | P3 |
| **Store** | | | |
| get / set / partial values / list / delete / exists / sizes | yes | yes | — |
| shift / reindex arrays | `shift_array`, `reindex_array` | no | P3 |
| **Other** | | | |
| Logging | `set_logs_filter` | `Logging.initialize(filter)`, to standard error | P3 (no Java logging bridge) |
| Zarr dtypes float16, complex, string, datetime | zarr-python | not in zarr-java (upstream) | P2, upstream |
