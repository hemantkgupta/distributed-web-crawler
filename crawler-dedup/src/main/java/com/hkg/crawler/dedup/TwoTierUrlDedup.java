package com.hkg.crawler.dedup;

import com.hkg.crawler.common.CanonicalUrl;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-tier URL deduplication: Bloom filter front gate + exact-set
 * backstop. The architectural answer to single-tier-Bloom's silent
 * coverage erosion (see blog §9 and {@link BloomFilter}'s class doc).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Query Bloom. If "definitely new", record in both tiers and
 *       return {@link UrlDedupVerdict#NEW} — fast path, O(k) hash ops.</li>
 *   <li>If Bloom says "probably seen", consult the exact set:
 *       <ul>
 *         <li>If present → real duplicate, return DUPLICATE.</li>
 *         <li>If absent → Bloom false positive, increment counter,
 *             record in exact set, return NEW.</li>
 *       </ul></li>
 * </ol>
 *
 * <p>The exact set in this implementation is an in-memory
 * {@code ConcurrentHashMap.newKeySet()}. At billion-URL scale this is
 * replaced by a DRUM-style disk-first repository (IRLbot) — Checkpoint 11.
 *
 * <p>Thread-safe; designed for concurrent calls from many workers.
 */
public final class TwoTierUrlDedup implements UrlDedup {

    private final BloomFilter bloom;
    private final ExactUrlSet exactSet;

    private final AtomicLong cQueries           = new AtomicLong();
    private final AtomicLong cNew               = new AtomicLong();
    private final AtomicLong cDuplicate         = new AtomicLong();
    private final AtomicLong cBloomNegative     = new AtomicLong();
    private final AtomicLong cBloomPositive     = new AtomicLong();
    private final AtomicLong cBloomFalsePositive = new AtomicLong();

    /** Default constructor: in-memory exact-set backstop. */
    public TwoTierUrlDedup(BloomFilter bloom) {
        this(bloom, new InMemoryExactUrlSet());
    }

    /** Constructor with explicit exact-set backstop (e.g., {@link RocksDbExactUrlSet}). */
    public TwoTierUrlDedup(BloomFilter bloom, ExactUrlSet exactSet) {
        this.bloom    = bloom;
        this.exactSet = exactSet;
    }

    /** Convenience: create a TwoTierUrlDedup sized for n URLs at fpr (in-memory backstop). */
    public static TwoTierUrlDedup forCapacity(long expectedUrls, double targetFpr) {
        return new TwoTierUrlDedup(BloomFilter.create(expectedUrls, targetFpr));
    }

    @Override
    public UrlDedupVerdict recordIfNew(CanonicalUrl url) {
        cQueries.incrementAndGet();
        String key = url.value();

        if (!bloom.mightContain(key)) {
            // Definitely new. Fast path.
            cBloomNegative.incrementAndGet();
            bloom.add(key);
            exactSet.add(key);
            cNew.incrementAndGet();
            return UrlDedupVerdict.NEW;
        }

        // Bloom says "probably seen" — consult exact set to disambiguate.
        cBloomPositive.incrementAndGet();
        if (exactSet.contains(key)) {
            cDuplicate.incrementAndGet();
            return UrlDedupVerdict.DUPLICATE;
        }

        // Bloom false positive — recover the URL.
        cBloomFalsePositive.incrementAndGet();
        bloom.add(key);
        exactSet.add(key);
        cNew.incrementAndGet();
        return UrlDedupVerdict.NEW;
    }

    @Override
    public UrlDedupVerdict probe(CanonicalUrl url) {
        // Read-only; does not increment counters and does not mutate state.
        if (!bloom.mightContain(url.value())) return UrlDedupVerdict.NEW;
        if (exactSet.contains(url.value()))    return UrlDedupVerdict.DUPLICATE;
        return UrlDedupVerdict.NEW;
    }

    @Override
    public UrlDedupStats stats() {
        return new UrlDedupStats(
            cQueries.get(),
            cNew.get(),
            cDuplicate.get(),
            cBloomNegative.get(),
            cBloomPositive.get(),
            cBloomFalsePositive.get(),
            exactSet.size(),
            bloom.bitCount(),
            bloom.addedCount(),
            bloom.estimatedFalsePositiveRate()
        );
    }

    /** Test-only access to the underlying Bloom filter. */
    public BloomFilter bloom() { return bloom; }
}
