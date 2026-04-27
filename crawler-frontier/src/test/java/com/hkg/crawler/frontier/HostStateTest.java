package com.hkg.crawler.frontier;

import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostStateTest {

    private final Host host = Host.of("example.com");
    private final Instant t0 = Instant.parse("2026-04-27T12:00:00Z");

    @Test
    void initial_state() {
        HostState s = new HostState(host, t0);
        assertThat(s.host()).isEqualTo(host);
        assertThat(s.nextFetchTime()).isEqualTo(t0);
        assertThat(s.crawlDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(s.backoffFactor()).isEqualTo(1.0);
        assertThat(s.consecutiveErrors()).isZero();
        assertThat(s.isQuarantined()).isFalse();
    }

    @Test
    void record_claim_advances_clock_by_effective_delay() {
        HostState s = new HostState(host, t0);
        s.recordClaim(t0);
        assertThat(s.nextFetchTime()).isEqualTo(t0.plus(Duration.ofSeconds(1)));
    }

    @Test
    void backoff_doubles_on_5xx() {
        HostState s = new HostState(host, t0);
        s.recordVerdict(FetchOutcome.SERVER_ERROR_5XX, t0);
        assertThat(s.backoffFactor()).isEqualTo(2.0);
        assertThat(s.consecutiveErrors()).isEqualTo(1);

        s.recordVerdict(FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(1));
        assertThat(s.backoffFactor()).isEqualTo(4.0);
        assertThat(s.consecutiveErrors()).isEqualTo(2);
    }

    @Test
    void backoff_doubles_on_429() {
        HostState s = new HostState(host, t0);
        s.recordVerdict(FetchOutcome.RATE_LIMITED_429, t0);
        assertThat(s.backoffFactor()).isEqualTo(2.0);
    }

    @Test
    void backoff_recovers_on_success() {
        HostState s = new HostState(host, t0);
        s.recordVerdict(FetchOutcome.SERVER_ERROR_5XX, t0);
        s.recordVerdict(FetchOutcome.SERVER_ERROR_5XX, t0);
        assertThat(s.backoffFactor()).isEqualTo(4.0);

        s.recordVerdict(FetchOutcome.SUCCESS_200, t0.plusSeconds(1));
        assertThat(s.backoffFactor()).isEqualTo(4.0 * 0.95);
        assertThat(s.consecutiveErrors()).isZero();
    }

    @Test
    void success_floor_at_1() {
        HostState s = new HostState(host, t0);
        for (int i = 0; i < 100; i++) {
            s.recordVerdict(FetchOutcome.SUCCESS_200, t0.plusSeconds(i));
        }
        assertThat(s.backoffFactor()).isEqualTo(1.0);
    }

    @Test
    void backoff_capped_at_max() {
        HostState s = new HostState(host, t0);
        for (int i = 0; i < 100; i++) {
            s.recordVerdict(FetchOutcome.SERVER_ERROR_5XX, t0.plusSeconds(i));
        }
        assertThat(s.backoffFactor()).isEqualTo(HostState.MAX_BACKOFF);
    }

    @Test
    void four_xx_does_not_change_backoff() {
        HostState s = new HostState(host, t0);
        s.recordVerdict(FetchOutcome.CLIENT_ERROR_4XX, t0);
        assertThat(s.backoffFactor()).isEqualTo(1.0);
        assertThat(s.consecutiveErrors()).isZero();
    }

    @Test
    void timeout_treated_as_backoff_signal() {
        HostState s = new HostState(host, t0);
        s.recordVerdict(FetchOutcome.TIMEOUT, t0);
        assertThat(s.backoffFactor()).isEqualTo(2.0);
    }

    @Test
    void rejects_negative_or_zero_crawl_delay() {
        HostState s = new HostState(host, t0);
        assertThatThrownBy(() -> s.setCrawlDelay(Duration.ofSeconds(0)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.setCrawlDelay(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.setCrawlDelay(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effective_delay_scales_with_backoff() {
        HostState s = new HostState(host, t0);
        s.setCrawlDelay(Duration.ofSeconds(2));
        assertThat(s.effectiveDelay()).isEqualTo(Duration.ofSeconds(2));

        s.recordVerdict(FetchOutcome.SERVER_ERROR_5XX, t0);
        // backoff is 2.0 → effective delay = 2s × 2.0 = 4s
        assertThat(s.effectiveDelay()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void back_queue_depth_tracking() {
        HostState s = new HostState(host, t0);
        assertThat(s.backQueueDepth()).isZero();
        s.incrementBackQueueDepth();
        s.incrementBackQueueDepth();
        assertThat(s.backQueueDepth()).isEqualTo(2);
        s.decrementBackQueueDepth();
        assertThat(s.backQueueDepth()).isEqualTo(1);

        assertThatThrownBy(() -> {
            s.decrementBackQueueDepth();
            s.decrementBackQueueDepth();   // would go negative
        }).isInstanceOf(IllegalStateException.class);
    }
}
