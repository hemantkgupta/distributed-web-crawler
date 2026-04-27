package com.hkg.crawler.dns;

import com.hkg.crawler.common.AddressFamily;
import com.hkg.crawler.common.Host;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachingDnsResolverTest {

    private static final Host EXAMPLE = Host.of("example.com");
    private static final Host SHARED1 = Host.of("tenant-1.cdn.example");
    private static final Host SHARED2 = Host.of("tenant-2.cdn.example");

    private TestClock clock;
    private CountingUpstream upstream;
    private CachingDnsResolver resolver;

    @BeforeEach
    void setUp() throws UnknownHostException {
        clock = new TestClock(Instant.parse("2026-04-27T12:00:00Z"));
        upstream = new CountingUpstream();
        resolver = new CachingDnsResolver(upstream, clock,
            Duration.ofSeconds(60), Duration.ofHours(1),
            Duration.ofMinutes(5), 4);
    }

    @Test
    void positive_cache_returns_without_upstream_call() throws Exception {
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            DnsResult.Hit.class.getDeclaredConstructor(List.class, AddressFamily.class, Instant.class)
                .newInstance(List.of(InetAddress.getByName("93.184.216.34")),
                             AddressFamily.IPV4,
                             clock.instant().plusSeconds(300)));

        DnsResult first  = resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        DnsResult second = resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);

        assertThat(first).isInstanceOf(DnsResult.Hit.class);
        assertThat(second).isInstanceOf(DnsResult.Hit.class);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(1);

        DnsResolverStats stats = resolver.stats();
        assertThat(stats.upstreamCalls()).isEqualTo(1);
        assertThat(stats.positiveCacheHits()).isEqualTo(1);
        assertThat(stats.cacheHitRatio()).isEqualTo(0.5);
    }

    @Test
    void positive_cache_expires_per_ttl() throws Exception {
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            new DnsResult.Hit(
                List.of(InetAddress.getByName("93.184.216.34")),
                AddressFamily.IPV4,
                clock.instant().plusSeconds(120)));

        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);

        // Advance past TTL.
        clock.advance(Duration.ofSeconds(125));

        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(2);
    }

    @Test
    void negative_cache_returns_without_upstream_call() throws Exception {
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            new DnsResult.Miss(DnsErrorCode.NXDOMAIN, clock.instant().plusSeconds(60)));

        DnsResult first  = resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        DnsResult second = resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);

        assertThat(first).isInstanceOf(DnsResult.Miss.class);
        assertThat(second).isInstanceOf(DnsResult.Miss.class);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(1);
        assertThat(resolver.stats().negativeCacheHits()).isEqualTo(1);
    }

    @Test
    void negative_ttl_capped_at_5_minutes() throws Exception {
        // Upstream returns Miss with expiry 1 day out — but we cap at 5 minutes.
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            new DnsResult.Miss(DnsErrorCode.NXDOMAIN, clock.instant().plus(Duration.ofDays(1))));

        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);

        // 4 minutes later → still cached
        clock.advance(Duration.ofMinutes(4));
        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(1);

        // 6 minutes total → expired
        clock.advance(Duration.ofMinutes(2));
        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(2);
    }

    @Test
    void positive_ttl_floored_at_60_seconds() throws Exception {
        // Upstream returns Hit with TTL of 5s — we floor at 60s.
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            new DnsResult.Hit(
                List.of(InetAddress.getByName("1.2.3.4")),
                AddressFamily.IPV4,
                clock.instant().plusSeconds(5)));

        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);

        // 30 seconds later — still cached (5s upstream TTL was floored to 60s)
        clock.advance(Duration.ofSeconds(30));
        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(1);
    }

    @Test
    void coalescing_dedups_concurrent_lookups() throws Exception {
        // Block the upstream so concurrent callers all hit the in-flight gate.
        CompletableFuture<DnsResult> blocked = new CompletableFuture<>();
        upstream.willReturnFuture(EXAMPLE, AddressFamily.IPV4, blocked);

        CompletableFuture<DnsResult> a = resolver.resolve(EXAMPLE, AddressFamily.IPV4);
        CompletableFuture<DnsResult> b = resolver.resolve(EXAMPLE, AddressFamily.IPV4);
        CompletableFuture<DnsResult> c = resolver.resolve(EXAMPLE, AddressFamily.IPV4);

        // All three callers got the same in-flight future.
        assertThat(a).isSameAs(b);
        assertThat(b).isSameAs(c);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(1);

        blocked.complete(new DnsResult.Hit(
            List.of(InetAddress.getByName("1.2.3.4")),
            AddressFamily.IPV4,
            clock.instant().plusSeconds(300)));

        a.get(1, TimeUnit.SECONDS);
        DnsResolverStats stats = resolver.stats();
        assertThat(stats.coalescedRequests()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void ip_reverse_index_tracks_shared_hosts() throws Exception {
        InetAddress sharedIp = InetAddress.getByName("192.0.2.1");
        upstream.willReturn(SHARED1, AddressFamily.IPV4,
            new DnsResult.Hit(List.of(sharedIp), AddressFamily.IPV4,
                clock.instant().plus(Duration.ofMinutes(10))));
        upstream.willReturn(SHARED2, AddressFamily.IPV4,
            new DnsResult.Hit(List.of(sharedIp), AddressFamily.IPV4,
                clock.instant().plus(Duration.ofMinutes(10))));

        resolver.resolve(SHARED1, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        resolver.resolve(SHARED2, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);

        assertThat(resolver.hostsSharingIp(sharedIp))
            .containsExactlyInAnyOrder(SHARED1, SHARED2);
    }

    @Test
    void invalidate_evicts_cache_and_updates_reverse_index() throws Exception {
        InetAddress ip = InetAddress.getByName("203.0.113.5");
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            new DnsResult.Hit(List.of(ip), AddressFamily.IPV4,
                clock.instant().plus(Duration.ofMinutes(10))));

        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        assertThat(resolver.hostsSharingIp(ip)).contains(EXAMPLE);

        resolver.invalidate(EXAMPLE);
        assertThat(resolver.hostsSharingIp(ip)).doesNotContain(EXAMPLE);

        resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(2);
    }

    @Test
    void upstream_exception_becomes_timeout_miss() throws Exception {
        CompletableFuture<DnsResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("network unreachable"));
        upstream.willReturnFuture(EXAMPLE, AddressFamily.IPV4, failed);

        DnsResult result = resolver.resolve(EXAMPLE, AddressFamily.IPV4)
            .get(1, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(DnsResult.Miss.class);
        assertThat(((DnsResult.Miss) result).error()).isEqualTo(DnsErrorCode.TIMEOUT);
    }

    @Test
    void different_address_families_cached_separately() throws Exception {
        upstream.willReturn(EXAMPLE, AddressFamily.IPV4,
            new DnsResult.Hit(List.of(InetAddress.getByName("1.2.3.4")),
                AddressFamily.IPV4, clock.instant().plus(Duration.ofMinutes(10))));
        upstream.willReturn(EXAMPLE, AddressFamily.IPV6,
            new DnsResult.Hit(List.of(InetAddress.getByName("2001:db8::1")),
                AddressFamily.IPV6, clock.instant().plus(Duration.ofMinutes(10))));

        DnsResult v4 = resolver.resolve(EXAMPLE, AddressFamily.IPV4).get(1, TimeUnit.SECONDS);
        DnsResult v6 = resolver.resolve(EXAMPLE, AddressFamily.IPV6).get(1, TimeUnit.SECONDS);

        assertThat(v4).isInstanceOf(DnsResult.Hit.class);
        assertThat(v6).isInstanceOf(DnsResult.Hit.class);
        assertThat(((DnsResult.Hit) v4).family()).isEqualTo(AddressFamily.IPV4);
        assertThat(((DnsResult.Hit) v6).family()).isEqualTo(AddressFamily.IPV6);

        // Each family triggers a separate upstream call.
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV4)).isEqualTo(1);
        assertThat(upstream.callCount(EXAMPLE, AddressFamily.IPV6)).isEqualTo(1);
    }

    // -------- test helpers ------------------------------------------------

    /** Test upstream that returns canned responses keyed by (host, family). */
    private static final class CountingUpstream implements UpstreamResolver {
        private final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<DnsResult>> canned =
            new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> calls =
            new java.util.concurrent.ConcurrentHashMap<>();

        void willReturn(Host h, AddressFamily f, DnsResult r) {
            canned.put(key(h, f), CompletableFuture.completedFuture(r));
        }

        void willReturnFuture(Host h, AddressFamily f, CompletableFuture<DnsResult> r) {
            canned.put(key(h, f), r);
        }

        int callCount(Host h, AddressFamily f) {
            AtomicInteger c = calls.get(key(h, f));
            return c == null ? 0 : c.get();
        }

        @Override
        public CompletableFuture<DnsResult> lookup(Host host, AddressFamily family) {
            calls.computeIfAbsent(key(host, family), k -> new AtomicInteger())
                 .incrementAndGet();
            CompletableFuture<DnsResult> f = canned.get(key(host, family));
            if (f == null) {
                return CompletableFuture.completedFuture(
                    new DnsResult.Miss(DnsErrorCode.OTHER,
                        Instant.now().plusSeconds(60)));
            }
            return f;
        }

        private static String key(Host h, AddressFamily f) {
            return h.value() + "|" + f.name();
        }
    }

    /** Mutable clock for time-based tests. */
    private static final class TestClock extends Clock {
        private Instant now;
        TestClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }
}
