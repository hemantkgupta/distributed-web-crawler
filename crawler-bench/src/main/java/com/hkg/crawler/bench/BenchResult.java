package com.hkg.crawler.bench;

import java.time.Duration;

/**
 * Result of one bench run. Captures throughput + latency percentiles
 * for ad-hoc load tests against any of the crawler services.
 */
public record BenchResult(
    String benchName,
    long  totalOperations,
    long  errorCount,
    Duration wallTime,
    double throughputPerSecond,
    long latencyP50Ns,
    long latencyP99Ns,
    long latencyMaxNs
) {
    public BenchResult {
        if (totalOperations < 0 || errorCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }

    public String summary() {
        return String.format(
            "%s: %d ops in %d ms → %.0f ops/s | p50=%dµs p99=%dµs max=%dµs | %d errors",
            benchName, totalOperations, wallTime.toMillis(), throughputPerSecond,
            latencyP50Ns / 1_000, latencyP99Ns / 1_000, latencyMaxNs / 1_000,
            errorCount);
    }
}
