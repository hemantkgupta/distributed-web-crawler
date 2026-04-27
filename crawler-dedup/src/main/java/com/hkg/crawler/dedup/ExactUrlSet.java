package com.hkg.crawler.dedup;

/**
 * Backstop tier of the two-tier URL dedup architecture. The exact-set
 * resolves the {@link BloomFilter}'s "probably seen" verdicts into
 * "yes definitely seen" or "Bloom false positive — recover this URL."
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@code InMemoryExactUrlSet} — backed by
 *       {@link java.util.concurrent.ConcurrentHashMap}; default for the
 *       single-shard setup.</li>
 *   <li>{@link RocksDbExactUrlSet} — disk-first, bucket-sharded
 *       RocksDB-backed implementation for >10⁹ URLs. The IRLbot DRUM
 *       lineage: batched sequential I/O instead of random-access lookup.</li>
 * </ul>
 *
 * <p>The interface is identical so the rest of the system doesn't change
 * when swapping backends; you choose the implementation by RAM budget.
 */
public interface ExactUrlSet extends AutoCloseable {

    /**
     * Add {@code url} to the set. Returns {@code true} iff the URL was
     * not already present.
     */
    boolean add(String url);

    /** Pure query: is {@code url} already in the set? */
    boolean contains(String url);

    /** Total number of distinct URLs in the set. */
    long size();

    @Override
    void close();
}
