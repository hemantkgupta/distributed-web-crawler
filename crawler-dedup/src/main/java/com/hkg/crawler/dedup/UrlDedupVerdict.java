package com.hkg.crawler.dedup;

/**
 * Verdict from the URL deduplication tier.
 *
 * <p>The two-tier design ({@link TwoTierUrlDedup}) returns:
 * <ul>
 *   <li>{@link #NEW} — URL has not been seen; admit to frontier.</li>
 *   <li>{@link #DUPLICATE} — URL has been seen exactly (confirmed by
 *       the exact-set tier); reject.</li>
 * </ul>
 *
 * <p>The Bloom filter alone can only return DEFINITELY_NEW or
 * PROBABLY_SEEN; the exact-set tier resolves PROBABLY_SEEN into
 * NEW or DUPLICATE without ambiguity.
 */
public enum UrlDedupVerdict {
    NEW,
    DUPLICATE
}
