package com.hkg.crawler.fetcher;

import com.hkg.crawler.common.CanonicalUrl;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Request to the {@link HttpFetcher}.
 *
 * <p>The conditional GET fields ({@link #conditionalEtag},
 * {@link #conditionalLastModified}) are populated by the recrawl
 * scheduler from prior fetch state. Per RFC 9110, the server is required
 * to ignore {@code If-Modified-Since} when {@code If-None-Match} is
 * present — we send both for compatibility.
 */
public record FetchRequest(
    CanonicalUrl url,
    Optional<String>  conditionalEtag,
    Optional<Instant> conditionalLastModified,
    Duration connectTimeout,
    Duration readTimeout,
    Duration totalTimeout,
    int      maxRedirects,
    long     maxBodyBytes,
    String   userAgent
) {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_READ_TIMEOUT    = Duration.ofSeconds(30);
    public static final Duration DEFAULT_TOTAL_TIMEOUT   = Duration.ofSeconds(60);
    public static final int      DEFAULT_MAX_REDIRECTS   = 5;
    public static final long     DEFAULT_MAX_BODY_BYTES  = 10L * 1024 * 1024;   // 10 MB
    public static final String   DEFAULT_USER_AGENT      =
        "DistributedWebCrawler/0.1 (+https://example.com/bot)";

    public FetchRequest {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(conditionalEtag, "conditionalEtag");
        Objects.requireNonNull(conditionalLastModified, "conditionalLastModified");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        Objects.requireNonNull(userAgent, "userAgent");
    }

    /** Construct a request with default timeouts and limits. */
    public static FetchRequest forUrl(CanonicalUrl url) {
        return new FetchRequest(
            url,
            Optional.empty(), Optional.empty(),
            DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_TOTAL_TIMEOUT,
            DEFAULT_MAX_REDIRECTS, DEFAULT_MAX_BODY_BYTES, DEFAULT_USER_AGENT
        );
    }

    public FetchRequest withConditionalEtag(String etag) {
        return new FetchRequest(url, Optional.of(etag), conditionalLastModified,
            connectTimeout, readTimeout, totalTimeout,
            maxRedirects, maxBodyBytes, userAgent);
    }

    public FetchRequest withConditionalLastModified(Instant lastModified) {
        return new FetchRequest(url, conditionalEtag, Optional.of(lastModified),
            connectTimeout, readTimeout, totalTimeout,
            maxRedirects, maxBodyBytes, userAgent);
    }
}
