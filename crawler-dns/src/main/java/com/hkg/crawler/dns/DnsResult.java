package com.hkg.crawler.dns;

import com.hkg.crawler.common.AddressFamily;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a DNS resolution.
 *
 * <p>The DNS Service caches results per {@code (host, family)}; the
 * {@link #expiresAt()} timestamp is the floor between the DNS-response
 * TTL and the configured cache cap (default 1 hour) — and at least the
 * configured floor (default 60 seconds) to prevent cache thrash on
 * aggressively short server-declared TTLs.
 */
public sealed interface DnsResult {

    Instant expiresAt();

    /** Successful resolution to one or more IPs. */
    record Hit(List<InetAddress> ips, AddressFamily family, Instant expiresAt)
        implements DnsResult {
        public Hit {
            Objects.requireNonNull(ips, "ips");
            if (ips.isEmpty()) {
                throw new IllegalArgumentException("Hit must have at least one IP");
            }
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(expiresAt, "expiresAt");
            ips = List.copyOf(ips);
        }
    }

    /** Negative resolution (NXDOMAIN, SERVFAIL, etc.). */
    record Miss(DnsErrorCode error, Instant expiresAt) implements DnsResult {
        public Miss {
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
