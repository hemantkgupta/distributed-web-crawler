package com.hkg.crawler.dedup;

import com.hkg.crawler.common.CanonicalUrl;

/**
 * Public contract for URL-level deduplication.
 *
 * <p>Production deployments use {@link TwoTierUrlDedup} (Bloom front
 * gate + exact-set backstop). Tests can use it too — there's no
 * meaningful "fake" implementation, because the failure mode the
 * service exists to prevent (Bloom false-positive coverage erosion) is
 * a real one and any in-memory test should reproduce it.
 */
public interface UrlDedup {

    /**
     * Decide whether {@code url} has been seen before. The first call
     * with a given URL returns {@link UrlDedupVerdict#NEW} and records
     * the URL; subsequent calls return {@link UrlDedupVerdict#DUPLICATE}.
     *
     * <p>This method is idempotent in the sense that the verdict for a
     * given URL is stable once recorded — but {@code recordIfNew} is
     * itself a write operation, so it's not a pure query.
     */
    UrlDedupVerdict recordIfNew(CanonicalUrl url);

    /**
     * Pure query: would this URL be considered NEW if we called
     * {@link #recordIfNew}? Useful for read-only inspection without
     * mutating dedup state.
     */
    UrlDedupVerdict probe(CanonicalUrl url);

    /** Snapshot of dedup-tier counters for observability. */
    UrlDedupStats stats();
}
