package com.hkg.crawler.dns;

import com.hkg.crawler.common.AddressFamily;
import com.hkg.crawler.common.Host;

import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Async DNS resolver — the public contract that the Fetcher (§6) calls
 * before each fetch and that the Frontier consults to populate per-IP
 * politeness clocks.
 *
 * <p>Production implementations wrap a colocated local recursive
 * resolver (Unbound). The contract is the same regardless of backend:
 * positive caching with TTL respect, negative caching with a 5-minute
 * cap, request coalescing for concurrent identical lookups, and a
 * reverse-index from IP → hosts that share that IP.
 */
public interface DnsResolver {

    /**
     * Resolve {@code host} to one or more IPs of the given family.
     * The returned future completes with either a {@link DnsResult.Hit}
     * or a {@link DnsResult.Miss}; it never throws.
     */
    CompletableFuture<DnsResult> resolve(Host host, AddressFamily family);

    /**
     * Hosts known to share the given IP. Used by the Frontier to
     * compute the per-IP politeness clock (see BUbiNG: politeness
     * must be enforced both per-host and per-IP).
     */
    Set<Host> hostsSharingIp(InetAddress ip);

    /**
     * Force-evict cached entries for {@code host}. Used by the Control
     * Plane when a takedown or operator action invalidates DNS state.
     */
    void invalidate(Host host);

    /** Snapshot of resolver internals for observability. */
    DnsResolverStats stats();
}
