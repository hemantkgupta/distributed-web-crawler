package com.hkg.crawler.frontier;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.Host;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A URL that has been claimed by a fetcher worker via
 * {@code Frontier.claimNext()}. The worker fetches this URL, then reports
 * the verdict back via {@code reportVerdict()}.
 *
 * <p>The {@link #expectedCrawlDelay()} hint reflects the host's current
 * politeness clock (with backoff applied). The Fetcher is not strictly
 * required to honor this — the Frontier already advanced
 * {@code nextFetchTime} on claim — but it's useful for cross-fetch budget
 * accounting.
 */
public record ClaimedUrl(
    CanonicalUrl url,
    Host host,
    Instant claimedAt,
    Duration expectedCrawlDelay
) {
    public ClaimedUrl {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(expectedCrawlDelay, "expectedCrawlDelay");
    }
}
