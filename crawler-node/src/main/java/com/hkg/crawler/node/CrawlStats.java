package com.hkg.crawler.node;

/**
 * Result summary from a {@link CrawlerNode#runUntilFrontierEmpty()} run.
 * Captures enough state for verification, observability, and the
 * "did the crawl actually do anything" check.
 */
public record CrawlStats(
    long urlsFetched,
    long urlsSucceeded,
    long urlsFailed,
    long urlsDisallowedByRobots,
    long bytesFetched,
    long warcRecordsWritten,
    long documentsEmitted,
    long linksDiscovered,
    long urlsDeduplicated,
    long stoppedReason   // 0=frontier-empty, 1=max-urls-reached
) {
    public static final long REASON_FRONTIER_EMPTY = 0;
    public static final long REASON_MAX_REACHED    = 1;
}
