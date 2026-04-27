package com.hkg.crawler.dedup;

/**
 * Snapshot of the URL dedup tier's counters. Surfacing these as
 * first-class metrics is part of the §9 design — the silent-coverage-
 * erosion failure mode of single-tier Bloom is detectable only via the
 * Bloom-positive-but-exact-set-says-NEW counter ({@link #bloomFalsePositives}).
 */
public record UrlDedupStats(
    long totalQueries,
    long newUrls,
    long duplicateUrls,
    long bloomNegatives,
    long bloomPositives,
    long bloomFalsePositives,
    long exactSetSize,
    int  bloomBitCount,
    long bloomAdds,
    double bloomEstimatedFpr
) {
    public double bloomFalsePositiveRate() {
        if (bloomPositives == 0) return 0;
        return (double) bloomFalsePositives / bloomPositives;
    }
}
