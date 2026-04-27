package com.hkg.crawler.robots;

import com.hkg.crawler.common.Host;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsCacheTest {

    private static final Host HOST = Host.of("example.com");

    private TestClock clock;
    private StubFetcher fetcher;
    private RobotsCache cache;

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-04-27T12:00:00Z"));
        fetcher = new StubFetcher();
        cache = new RobotsCache(fetcher, new RobotsParser(), clock);
    }

    @Test
    void four_xx_response_caches_synthetic_allow_all() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.clientError(404));

        RobotsRules rules = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(rules.source()).isEqualTo(RobotsRules.Source.SYNTHETIC_ALLOW_ALL_4XX);
        assertThat(rules.isAllowed("MyBot", "/anywhere"))
            .isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void five_xx_response_caches_synthetic_disallow_all() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.serverError(503));

        RobotsRules rules = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(rules.source()).isEqualTo(RobotsRules.Source.SYNTHETIC_DISALLOW_ALL_5XX);
        assertThat(rules.isAllowed("MyBot", "/anywhere"))
            .isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void network_failure_treated_as_5xx() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.networkError(
            RobotsFetcher.FetchError.TIMEOUT));

        RobotsRules rules = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(rules.source()).isEqualTo(RobotsRules.Source.SYNTHETIC_DISALLOW_ALL_5XX);
        assertThat(rules.isAllowed("MyBot", "/anywhere"))
            .isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void successful_response_parses_and_caches() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /private/\n"));

        RobotsRules rules = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(rules.source()).isEqualTo(RobotsRules.Source.FETCHED);
        assertThat(rules.isAllowed("MyBot", "/private/x"))
            .isEqualTo(RobotsRules.Verdict.DISALLOWED);
        assertThat(rules.isAllowed("MyBot", "/public/x"))
            .isEqualTo(RobotsRules.Verdict.ALLOWED);
    }

    @Test
    void cached_entry_served_without_refetch_within_ttl() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /a/\n"));

        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        clock.advance(Duration.ofHours(20));   // still within 24h TTL
        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);

        assertThat(fetcher.callCount(HOST)).isEqualTo(1);
    }

    @Test
    void expired_entry_triggers_refetch() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /a/\n"));

        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        clock.advance(Duration.ofHours(25));   // past 24h TTL
        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);

        assertThat(fetcher.callCount(HOST)).isEqualTo(2);
    }

    @Test
    void five_xx_entry_has_shorter_one_hour_ttl() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.serverError(503));

        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        clock.advance(Duration.ofMinutes(30));
        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);   // still cached
        assertThat(fetcher.callCount(HOST)).isEqualTo(1);

        clock.advance(Duration.ofMinutes(45));   // total 75 min; past 1h TTL
        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(fetcher.callCount(HOST)).isEqualTo(2);
    }

    @Test
    void stale_serve_when_refresh_fails_within_7_days() throws Exception {
        // First fetch succeeds.
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /admin/\n"));
        RobotsRules first = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(first.source()).isEqualTo(RobotsRules.Source.FETCHED);

        // Advance 25 hours, refresh fails with 5xx.
        clock.advance(Duration.ofHours(25));
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.serverError(500));

        RobotsRules served = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(served.source()).isEqualTo(RobotsRules.Source.STALE_SERVE);
        // Stale-serve still has the original /admin/ rule
        assertThat(served.isAllowed("MyBot", "/admin/x"))
            .isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void invalidate_forces_refetch() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /old/\n"));
        cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);

        cache.invalidate(HOST);

        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /new/\n"));
        RobotsRules updated = cache.rulesFor(HOST).get(1, TimeUnit.SECONDS);
        assertThat(updated.isAllowed("MyBot", "/old/x"))
            .isEqualTo(RobotsRules.Verdict.ALLOWED);
        assertThat(updated.isAllowed("MyBot", "/new/x"))
            .isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    @Test
    void is_allowed_convenience_method() throws Exception {
        fetcher.willReturn(HOST, RobotsFetcher.RobotsResponse.ok(200,
            "User-agent: *\nDisallow: /private/\n"));

        RobotsRules.Verdict v1 = cache.isAllowed(HOST, "MyBot", "/public/x")
            .get(1, TimeUnit.SECONDS);
        RobotsRules.Verdict v2 = cache.isAllowed(HOST, "MyBot", "/private/x")
            .get(1, TimeUnit.SECONDS);

        assertThat(v1).isEqualTo(RobotsRules.Verdict.ALLOWED);
        assertThat(v2).isEqualTo(RobotsRules.Verdict.DISALLOWED);
    }

    // -------- helpers ------------------------------------------------------

    private static final class StubFetcher implements RobotsFetcher {
        private final ConcurrentHashMap<Host, CompletableFuture<RobotsResponse>> canned =
            new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Host, AtomicInteger> calls = new ConcurrentHashMap<>();

        void willReturn(Host h, RobotsResponse r) {
            canned.put(h, CompletableFuture.completedFuture(r));
        }

        int callCount(Host h) {
            AtomicInteger c = calls.get(h);
            return c == null ? 0 : c.get();
        }

        @Override
        public CompletableFuture<RobotsResponse> fetch(Host host) {
            calls.computeIfAbsent(host, k -> new AtomicInteger()).incrementAndGet();
            return canned.getOrDefault(host,
                CompletableFuture.completedFuture(RobotsResponse.networkError(FetchError.OTHER)));
        }
    }

    private static final class TestClock extends Clock {
        private Instant now;
        TestClock(Instant start) { this.now = start; }
        void advance(Duration d) { this.now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }
}
