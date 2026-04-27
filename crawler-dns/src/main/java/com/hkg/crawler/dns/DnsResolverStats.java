package com.hkg.crawler.dns;

/**
 * Snapshot of the DNS resolver's internal counters for observability.
 * DNS is a known throughput bottleneck at crawler scale (BUbiNG: "essential
 * to run a local recursive DNS server"); these metrics belong on the
 * primary dashboard, not as ops afterthoughts.
 */
public record DnsResolverStats(
    long lookups,
    long positiveCacheHits,
    long negativeCacheHits,
    long coalescedRequests,
    long upstreamCalls,
    long timeouts,
    int  positiveCacheSize,
    int  negativeCacheSize,
    int  inFlightCount
) {
    public double cacheHitRatio() {
        long total = lookups;
        if (total == 0) return 0;
        return (positiveCacheHits + negativeCacheHits) / (double) total;
    }

    public double coalesceSavingsRatio() {
        long total = upstreamCalls + coalescedRequests;
        if (total == 0) return 0;
        return coalescedRequests / (double) total;
    }
}
