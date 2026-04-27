package com.hkg.crawler.frontier;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;
import com.hkg.crawler.common.PriorityClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryFrontierTest {

    private InMemoryFrontier frontier;
    private Instant t0;

    @BeforeEach
    void setUp() {
        frontier = new InMemoryFrontier();
        // Use Instant.now() with a small forward cushion: InMemoryFrontier.enqueue()
        // captures Instant.now() internally to set initial host clocks, so t0 must
        // be ≥ that capture for claims to succeed. The 1s cushion absorbs the tiny
        // delta between this capture and per-test enqueue calls.
        t0 = Instant.now().plusSeconds(1);
    }

    private FrontierUrl url(String s, PriorityClass cls) {
        return new FrontierUrl(CanonicalUrl.of(s), cls, t0);
    }

    @Test
    @DisplayName("first URL for an idle host is immediately claimable")
    void single_url_immediately_claimable() {
        frontier.enqueue(url("http://example.com/a", PriorityClass.HIGH_OPIC));

        Optional<ClaimedUrl> claim = frontier.claimNext(t0);
        assertThat(claim).isPresent();
        assertThat(claim.get().url().value()).isEqualTo("http://example.com/a");
        assertThat(claim.get().host().value()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("after claim, the same host is not re-claimable until crawl-delay elapses")
    void claim_advances_host_clock() {
        frontier.enqueue(url("http://example.com/a", PriorityClass.HIGH_OPIC));
        frontier.enqueue(url("http://example.com/b", PriorityClass.HIGH_OPIC));

        Optional<ClaimedUrl> first = frontier.claimNext(t0);
        assertThat(first).isPresent();

        // Immediately after, even though queue has more URLs, the host's clock has advanced.
        Optional<ClaimedUrl> tooSoon = frontier.claimNext(t0);
        assertThat(tooSoon).isEmpty();

        // Once enough wall-clock time has passed, the next URL becomes claimable.
        Instant later = t0.plusSeconds(2);
        Optional<ClaimedUrl> second = frontier.claimNext(later);
        assertThat(second).isPresent();
        assertThat(second.get().url().value()).isEqualTo("http://example.com/b");
    }

    @Test
    @DisplayName("workers do not idle while another host has work")
    void multiple_hosts_no_idling() {
        frontier.enqueue(url("http://a.com/x", PriorityClass.HIGH_OPIC));
        frontier.enqueue(url("http://b.com/y", PriorityClass.HIGH_OPIC));

        // Claim from a.com first.
        Optional<ClaimedUrl> first = frontier.claimNext(t0);
        assertThat(first).isPresent();

        // a.com is now cooling down; b.com should still be ready immediately.
        Optional<ClaimedUrl> second = frontier.claimNext(t0);
        assertThat(second).isPresent();
        assertThat(second.get().host().value()).isEqualTo("b.com");
        assertThat(first.get().host().value()).isEqualTo("a.com");
    }

    @Test
    @DisplayName("heap orders hosts by next_fetch_time")
    void heap_orders_by_next_fetch_time() {
        frontier.enqueue(url("http://slower.com/a", PriorityClass.HIGH_OPIC));
        frontier.enqueue(url("http://faster.com/a", PriorityClass.HIGH_OPIC));

        // Claim slower.com first; its clock advances.
        ClaimedUrl c1 = frontier.claimNext(t0).orElseThrow();
        // Claim faster.com next (still on initial clock).
        ClaimedUrl c2 = frontier.claimNext(t0).orElseThrow();

        // After both are cooling down, neither should be claimable now.
        assertThat(frontier.claimNext(t0)).isEmpty();

        // Confirm the two claims came from the two distinct hosts.
        assertThat(c1.host()).isNotEqualTo(c2.host());
    }

    @Test
    @DisplayName("5xx verdict doubles backoff factor; success recovers")
    void backoff_factor_responds_to_verdicts() {
        Host host = Host.of("flaky.com");
        frontier.enqueue(url("http://flaky.com/a", PriorityClass.HIGH_OPIC));
        ClaimedUrl c1 = frontier.claimNext(t0).orElseThrow();

        // Initially backoff is 1.0
        assertThat(frontier.hostStateFor(host).orElseThrow().backoffFactor())
            .isEqualTo(1.0);

        // Report 503 → backoff doubles to 2.0
        frontier.reportVerdict(c1.url(), FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(1));
        assertThat(frontier.hostStateFor(host).orElseThrow().backoffFactor())
            .isEqualTo(2.0);

        // Report another 503 → 4.0
        frontier.enqueue(url("http://flaky.com/b", PriorityClass.HIGH_OPIC));
        ClaimedUrl c2 = frontier.claimNext(t0.plusSeconds(60)).orElseThrow();
        frontier.reportVerdict(c2.url(), FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(70));
        assertThat(frontier.hostStateFor(host).orElseThrow().backoffFactor())
            .isEqualTo(4.0);

        // Report a success → backoff drops 4.0 × 0.95 = 3.8
        frontier.enqueue(url("http://flaky.com/c", PriorityClass.HIGH_OPIC));
        ClaimedUrl c3 = frontier.claimNext(t0.plusSeconds(300)).orElseThrow();
        frontier.reportVerdict(c3.url(), FetchOutcome.SUCCESS_200, t0.plusSeconds(301));
        assertThat(frontier.hostStateFor(host).orElseThrow().backoffFactor())
            .isCloseTo(3.8, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("4xx verdict does not affect backoff (host is fine; URL is gone)")
    void four_xx_does_not_change_backoff() {
        frontier.enqueue(url("http://example.com/missing", PriorityClass.HIGH_OPIC));
        ClaimedUrl claim = frontier.claimNext(t0).orElseThrow();

        double before = frontier.hostStateFor(claim.host()).orElseThrow().backoffFactor();
        frontier.reportVerdict(claim.url(), FetchOutcome.CLIENT_ERROR_4XX, t0.plusSeconds(1));
        double after = frontier.hostStateFor(claim.host()).orElseThrow().backoffFactor();

        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("backoff factor is capped at MAX_BACKOFF (64.0)")
    void backoff_capped() {
        frontier.enqueue(url("http://capped.com/a", PriorityClass.HIGH_OPIC));
        ClaimedUrl claim = frontier.claimNext(t0).orElseThrow();

        // Hammer the host with errors.
        Instant now = t0;
        for (int i = 0; i < 20; i++) {
            now = now.plusSeconds(3600);
            frontier.reportVerdict(claim.url(), FetchOutcome.SERVER_ERROR_5XX, now);
        }
        assertThat(frontier.hostStateFor(claim.host()).orElseThrow().backoffFactor())
            .isLessThanOrEqualTo(HostState.MAX_BACKOFF);
        assertThat(frontier.hostStateFor(claim.host()).orElseThrow().backoffFactor())
            .isEqualTo(HostState.MAX_BACKOFF);
    }

    @Test
    @DisplayName("quarantined host is not claimable")
    void quarantine_blocks_claims() {
        frontier.enqueue(url("http://blocked.com/a", PriorityClass.HIGH_OPIC));
        frontier.quarantineHost(Host.of("blocked.com"));

        Optional<ClaimedUrl> claim = frontier.claimNext(t0);
        assertThat(claim).isEmpty();

        // Releasing the host re-admits work.
        frontier.releaseHost(Host.of("blocked.com"));
        Optional<ClaimedUrl> claim2 = frontier.claimNext(t0);
        assertThat(claim2).isPresent();
    }

    @Test
    @DisplayName("stats reports queue sizes correctly")
    void stats_reflects_state() {
        frontier.enqueue(url("http://a.com/1", PriorityClass.HIGH_OPIC));
        frontier.enqueue(url("http://a.com/2", PriorityClass.HIGH_OPIC));
        frontier.enqueue(url("http://b.com/1", PriorityClass.MEDIUM_OPIC));

        FrontierStats stats = frontier.stats();
        assertThat(stats.activeHostCount()).isEqualTo(2);
        assertThat(stats.totalUrlsInBackQueues()).isEqualTo(3);
        assertThat(stats.readyHostCount()).isEqualTo(2);
        assertThat(stats.earliestReadyTime()).isPresent();
    }

    @Test
    @DisplayName("multiple URLs to one host all flow into that host's back queue")
    void multiple_urls_one_host() {
        for (int i = 0; i < 10; i++) {
            frontier.enqueue(url("http://big.com/p" + i, PriorityClass.MEDIUM_OPIC));
        }
        assertThat(frontier.backQueueDepth(Host.of("big.com"))).isEqualTo(10);
    }

    @Test
    @DisplayName("empty heap returns empty claim")
    void empty_frontier_returns_empty() {
        assertThat(frontier.claimNext(t0)).isEmpty();
    }

    @Test
    @DisplayName("crawl-delay override is respected")
    void crawl_delay_override() {
        frontier.enqueue(url("http://slow.com/a", PriorityClass.HIGH_OPIC));
        Host host = Host.of("slow.com");
        frontier.hostStateFor(host).orElseThrow().setCrawlDelay(Duration.ofSeconds(10));

        ClaimedUrl claim = frontier.claimNext(t0).orElseThrow();
        // After claim, next_fetch_time should be ~10s out.
        Instant nextFetch = frontier.hostStateFor(host).orElseThrow().nextFetchTime();
        assertThat(Duration.between(t0, nextFetch).getSeconds()).isGreaterThanOrEqualTo(10);
    }
}
