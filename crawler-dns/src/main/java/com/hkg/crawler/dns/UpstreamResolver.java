package com.hkg.crawler.dns;

import com.hkg.crawler.common.AddressFamily;
import com.hkg.crawler.common.Host;

import java.util.concurrent.CompletableFuture;

/**
 * SPI for the actual DNS lookup the {@link CachingDnsResolver} delegates
 * to on cache miss. In production this is a colocated local recursive
 * resolver (Unbound) accessed via the JDK or a native client; in tests
 * it's a stub that returns canned responses.
 *
 * <p>The contract: {@code lookup(host, family)} performs one DNS query
 * with a bounded timeout and completes the future with a
 * {@link DnsResult}. Implementations are expected to be async (no
 * blocking the caller) and idempotent.
 */
@FunctionalInterface
public interface UpstreamResolver {
    CompletableFuture<DnsResult> lookup(Host host, AddressFamily family);
}
