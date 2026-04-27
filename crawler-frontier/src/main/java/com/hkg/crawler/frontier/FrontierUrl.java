package com.hkg.crawler.frontier;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.PriorityClass;

import java.time.Instant;
import java.util.Objects;

/**
 * A URL waiting in the Frontier — either in a front queue (waiting to be
 * routed to a back queue) or in a back queue (waiting to be claimed by a
 * fetcher worker).
 *
 * <p>Carries enough metadata for the front-queue selector to make priority
 * decisions and for the Storage / Importance services to attribute the
 * fetch correctly.
 */
public record FrontierUrl(
    CanonicalUrl url,
    PriorityClass priorityClass,
    Instant discoveredAt
) {
    public FrontierUrl {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(priorityClass, "priorityClass");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
    }

    public static FrontierUrl of(CanonicalUrl url, PriorityClass cls, Instant now) {
        return new FrontierUrl(url, cls, now);
    }
}
