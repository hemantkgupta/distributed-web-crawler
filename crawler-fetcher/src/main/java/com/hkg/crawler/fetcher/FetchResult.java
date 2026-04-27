package com.hkg.crawler.fetcher;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of one HTTP fetch attempt. Carries enough information for:
 * <ul>
 *   <li>The Frontier to update host politeness state via
 *       {@link #outcome()} ({@code SUCCESS_200}, {@code SERVER_ERROR_5XX},
 *       etc.).</li>
 *   <li>The Parser to consume {@link #body()}.</li>
 *   <li>The Recrawl Scheduler to store {@link #etag()} /
 *       {@link #lastModified()} for future conditional GET.</li>
 *   <li>The operational stream to report fetch latency p50/p99 from
 *       {@link #fetchDurationMs()}.</li>
 * </ul>
 */
public record FetchResult(
    CanonicalUrl url,
    CanonicalUrl finalUrl,                  // after redirects
    int httpStatus,
    Map<String, List<String>> headers,
    Optional<byte[]> body,                   // empty for 304 / HEAD-only / errors
    Optional<String> contentType,
    Optional<String> contentEncoding,
    Optional<String> etag,
    Optional<Instant> lastModified,
    long fetchDurationMs,
    int  redirectHopCount,
    boolean conditionalUsed,
    boolean conditionalMatched,              // true iff server returned 304
    FetchOutcome outcome,
    Optional<String> errorMessage           // populated on NETWORK_ERROR/TIMEOUT
) {
    public FetchResult {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(finalUrl, "finalUrl");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(contentEncoding, "contentEncoding");
        Objects.requireNonNull(etag, "etag");
        Objects.requireNonNull(lastModified, "lastModified");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(errorMessage, "errorMessage");
        headers = Map.copyOf(headers);
    }
}
