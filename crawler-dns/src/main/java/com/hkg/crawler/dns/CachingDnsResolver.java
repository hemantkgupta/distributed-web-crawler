package com.hkg.crawler.dns;

import com.hkg.crawler.common.AddressFamily;
import com.hkg.crawler.common.Host;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production-shaped DNS resolver with the operational discipline that
 * separates a crawler that scales from one that doesn't:
 *
 * <ul>
 *   <li><b>Positive cache</b> — keyed on {@code (host, family)}, TTL
 *       respect with a configurable floor and cap.</li>
 *   <li><b>Negative cache</b> — separate cache for NXDOMAIN/SERVFAIL
 *       with a 5-minute cap so a transient DNS misconfiguration doesn't
 *       blackout a host for a day.</li>
 *   <li><b>Request coalescing</b> — concurrent identical lookups share a
 *       single in-flight upstream call. At 50 fetcher workers all hitting
 *       the same hot host, this typically reduces upstream load 50×.</li>
 *   <li><b>Per-domain in-flight cap</b> — a slow nameserver for one
 *       domain cannot monopolize resolver capacity.</li>
 *   <li><b>IP reverse-index</b> — feeds the Frontier's per-IP politeness
 *       clock so shared-IP hosts (CDN tenants, shared hosting) don't
 *       bypass per-IP politeness.</li>
 * </ul>
 *
 * <p>The actual DNS query is delegated to an {@link UpstreamResolver}
 * (typically an async client to a colocated Unbound instance).
 *
 * <p>Thread-safe; designed to be invoked from many fetcher threads
 * concurrently.
 */
public final class CachingDnsResolver implements DnsResolver {

    public static final Duration DEFAULT_POSITIVE_TTL_FLOOR = Duration.ofSeconds(60);
    public static final Duration DEFAULT_POSITIVE_TTL_CAP   = Duration.ofHours(1);
    public static final Duration DEFAULT_NEGATIVE_TTL_CAP   = Duration.ofMinutes(5);
    public static final int      DEFAULT_PER_DOMAIN_LIMIT   = 4;

    private final UpstreamResolver upstream;
    private final Clock clock;
    private final Duration positiveTtlFloor;
    private final Duration positiveTtlCap;
    private final Duration negativeTtlCap;
    private final int perDomainLimit;

    /** Positive cache. Key is {@code (host, family)}. */
    private final ConcurrentHashMap<CacheKey, DnsResult.Hit> positiveCache =
        new ConcurrentHashMap<>();

    /** Negative cache. Same key shape; separate so we never serve a Hit when we have a Miss. */
    private final ConcurrentHashMap<CacheKey, DnsResult.Miss> negativeCache =
        new ConcurrentHashMap<>();

    /** In-flight coalescing. A single CompletableFuture per pending upstream call. */
    private final ConcurrentHashMap<CacheKey, CompletableFuture<DnsResult>> inFlight =
        new ConcurrentHashMap<>();

    /** Per-domain semaphores; cap concurrent upstream calls to one domain. */
    private final ConcurrentHashMap<Host, Semaphore> perDomainSemaphores =
        new ConcurrentHashMap<>();

    /** IP reverse-index: which hosts share each IP. */
    private final ConcurrentHashMap<InetAddress, Set<Host>> ipToHosts =
        new ConcurrentHashMap<>();

    // ---- counters -----------------------------------------------------
    private final AtomicLong cLookups        = new AtomicLong();
    private final AtomicLong cPositiveHits   = new AtomicLong();
    private final AtomicLong cNegativeHits   = new AtomicLong();
    private final AtomicLong cCoalesced      = new AtomicLong();
    private final AtomicLong cUpstreamCalls  = new AtomicLong();
    private final AtomicLong cTimeouts       = new AtomicLong();

    public CachingDnsResolver(UpstreamResolver upstream) {
        this(upstream, Clock.systemUTC(),
             DEFAULT_POSITIVE_TTL_FLOOR, DEFAULT_POSITIVE_TTL_CAP,
             DEFAULT_NEGATIVE_TTL_CAP, DEFAULT_PER_DOMAIN_LIMIT);
    }

    public CachingDnsResolver(UpstreamResolver upstream, Clock clock,
                               Duration positiveTtlFloor, Duration positiveTtlCap,
                               Duration negativeTtlCap, int perDomainLimit) {
        this.upstream         = upstream;
        this.clock            = clock;
        this.positiveTtlFloor = positiveTtlFloor;
        this.positiveTtlCap   = positiveTtlCap;
        this.negativeTtlCap   = negativeTtlCap;
        this.perDomainLimit   = perDomainLimit;
    }

    @Override
    public CompletableFuture<DnsResult> resolve(Host host, AddressFamily family) {
        cLookups.incrementAndGet();
        CacheKey key = new CacheKey(host, family);
        Instant now = clock.instant();

        // Positive cache check
        DnsResult.Hit hit = positiveCache.get(key);
        if (hit != null && hit.expiresAt().isAfter(now)) {
            cPositiveHits.incrementAndGet();
            return CompletableFuture.completedFuture(hit);
        }
        if (hit != null) {   // expired entry — evict
            positiveCache.remove(key, hit);
        }

        // Negative cache check
        DnsResult.Miss miss = negativeCache.get(key);
        if (miss != null && miss.expiresAt().isAfter(now)) {
            cNegativeHits.incrementAndGet();
            return CompletableFuture.completedFuture(miss);
        }
        if (miss != null) {
            negativeCache.remove(key, miss);
        }

        // Coalesce: if a lookup is already in-flight for this key, attach.
        CompletableFuture<DnsResult> existing = inFlight.get(key);
        if (existing != null) {
            cCoalesced.incrementAndGet();
            return existing;
        }

        // Acquire per-domain permit (non-blocking; returns a future that
        // completes after the lookup).
        return performUpstreamLookup(host, family, key);
    }

    private CompletableFuture<DnsResult> performUpstreamLookup(
            Host host, AddressFamily family, CacheKey key) {

        CompletableFuture<DnsResult> resultFuture = new CompletableFuture<>();
        // Race: another thread may have just inserted a future. computeIfAbsent
        // ensures only the winner does the upstream call.
        CompletableFuture<DnsResult> winner = inFlight.computeIfAbsent(key, k -> resultFuture);
        if (winner != resultFuture) {
            // We lost the race; attach to the winner's future.
            cCoalesced.incrementAndGet();
            return winner;
        }

        // We won the race. Acquire per-domain permit, perform upstream call.
        Semaphore sem = perDomainSemaphores.computeIfAbsent(host,
            h -> new Semaphore(perDomainLimit));

        sem.acquireUninterruptibly();
        cUpstreamCalls.incrementAndGet();

        upstream.lookup(host, family)
            .whenComplete((dnsResult, ex) -> {
                try {
                    if (ex != null) {
                        // Treat unexpected upstream failures as TIMEOUT for safety.
                        cTimeouts.incrementAndGet();
                        DnsResult.Miss timeout = new DnsResult.Miss(
                            DnsErrorCode.TIMEOUT,
                            clock.instant().plus(negativeTtlCap)
                        );
                        negativeCache.put(key, timeout);
                        resultFuture.complete(timeout);
                    } else {
                        cacheAndIndex(host, family, dnsResult);
                        resultFuture.complete(dnsResult);
                    }
                } finally {
                    sem.release();
                    inFlight.remove(key, resultFuture);
                }
            });

        return resultFuture;
    }

    private void cacheAndIndex(Host host, AddressFamily family, DnsResult result) {
        CacheKey key = new CacheKey(host, family);
        if (result instanceof DnsResult.Hit hit) {
            // Clamp expiration: the upstream's expiresAt is the lower bound;
            // our floor and cap further constrain it.
            Instant now = clock.instant();
            Instant clampedExpires = clampPositiveExpiry(now, hit.expiresAt());
            DnsResult.Hit cached = new DnsResult.Hit(hit.ips(), hit.family(), clampedExpires);
            positiveCache.put(key, cached);

            // Update the IP reverse-index so the Frontier can find shared-IP hosts.
            for (InetAddress ip : hit.ips()) {
                ipToHosts.compute(ip, (k, existing) -> {
                    Set<Host> updated = (existing == null)
                        ? Collections.synchronizedSet(new HashSet<>())
                        : existing;
                    updated.add(host);
                    return updated;
                });
            }
        } else if (result instanceof DnsResult.Miss miss) {
            // Clamp negative expiry to our cap.
            Instant now = clock.instant();
            Instant clampedExpires = miss.expiresAt().isAfter(now.plus(negativeTtlCap))
                ? now.plus(negativeTtlCap)
                : miss.expiresAt();
            negativeCache.put(key, new DnsResult.Miss(miss.error(), clampedExpires));
        }
    }

    private Instant clampPositiveExpiry(Instant now, Instant upstreamExpiry) {
        Instant minExpiry = now.plus(positiveTtlFloor);
        Instant maxExpiry = now.plus(positiveTtlCap);
        if (upstreamExpiry.isBefore(minExpiry)) return minExpiry;
        if (upstreamExpiry.isAfter(maxExpiry)) return maxExpiry;
        return upstreamExpiry;
    }

    @Override
    public Set<Host> hostsSharingIp(InetAddress ip) {
        Set<Host> hosts = ipToHosts.get(ip);
        return hosts == null ? Set.of() : Set.copyOf(hosts);
    }

    @Override
    public void invalidate(Host host) {
        for (AddressFamily f : AddressFamily.values()) {
            CacheKey key = new CacheKey(host, f);
            DnsResult.Hit removed = positiveCache.remove(key);
            negativeCache.remove(key);
            if (removed != null) {
                for (InetAddress ip : removed.ips()) {
                    Set<Host> set = ipToHosts.get(ip);
                    if (set != null) {
                        set.remove(host);
                        if (set.isEmpty()) ipToHosts.remove(ip, set);
                    }
                }
            }
        }
    }

    @Override
    public DnsResolverStats stats() {
        return new DnsResolverStats(
            cLookups.get(),
            cPositiveHits.get(),
            cNegativeHits.get(),
            cCoalesced.get(),
            cUpstreamCalls.get(),
            cTimeouts.get(),
            positiveCache.size(),
            negativeCache.size(),
            inFlight.size()
        );
    }

    private record CacheKey(Host host, AddressFamily family) {}
}
