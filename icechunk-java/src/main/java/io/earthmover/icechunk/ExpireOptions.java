package io.earthmover.icechunk;

import java.time.Instant;

/**
 * Optional settings for {@link Repository#expireSnapshots(Instant, ExpireOptions)}.
 *
 * <pre>{@code
 * repo.expireSnapshots(cutoff, ExpireOptions.builder().deleteExpiredBranches(true).build());
 * }</pre>
 */
public final class ExpireOptions {
    private static final ExpireOptions DEFAULTS = builder().build();

    private final boolean deleteExpiredBranches;
    private final boolean deleteExpiredTags;

    private ExpireOptions(Builder builder) {
        this.deleteExpiredBranches = builder.deleteExpiredBranches;
        this.deleteExpiredTags = builder.deleteExpiredTags;
    }

    /** Keep expired branches and tags. */
    public static ExpireOptions defaults() {
        return DEFAULTS;
    }

    /** A builder that starts from {@link #defaults()}. */
    public static Builder builder() {
        return new Builder();
    }

    String toJson(Instant olderThan) {
        return Json.object()
                .put("older_than", Json.timestamp("olderThan", olderThan))
                .put("delete_expired_branches", deleteExpiredBranches)
                .put("delete_expired_tags", deleteExpiredTags)
                .toString();
    }

    public static final class Builder {
        private boolean deleteExpiredBranches;
        private boolean deleteExpiredTags;

        private Builder() {}

        /**
         * Whether to delete branches whose tip is an expired snapshot. {@code main} is never deleted. Defaults to
         * false, which keeps those tips in the history.
         */
        public Builder deleteExpiredBranches(boolean deleteExpiredBranches) {
            this.deleteExpiredBranches = deleteExpiredBranches;
            return this;
        }

        /**
         * Whether to delete tags that point to an expired snapshot. Defaults to false, which keeps those snapshots in
         * the history. As with {@link Repository#deleteTag}, a deleted tag's name cannot be used again.
         */
        public Builder deleteExpiredTags(boolean deleteExpiredTags) {
            this.deleteExpiredTags = deleteExpiredTags;
            return this;
        }

        public ExpireOptions build() {
            return new ExpireOptions(this);
        }
    }
}
