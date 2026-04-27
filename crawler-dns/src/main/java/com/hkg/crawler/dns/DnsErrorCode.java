package com.hkg.crawler.dns;

/**
 * DNS resolution failure modes carried in {@link DnsResult.Miss}.
 *
 * <p>The Frontier and Fetcher distinguish these because the recovery
 * policy differs: NXDOMAIN is permanent (don't retry the host),
 * SERVFAIL is usually transient (retry after the negative-cache TTL),
 * TIMEOUT is transient and may signal resolver overload.
 */
public enum DnsErrorCode {
    NXDOMAIN,
    SERVFAIL,
    TIMEOUT,
    REFUSED,
    OTHER
}
