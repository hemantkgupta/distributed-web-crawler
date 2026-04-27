package com.hkg.crawler.common.observability;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMetricsTest {

    @Test
    void counter_aggregates_increments() {
        InMemoryMetrics m = new InMemoryMetrics();
        m.counter("fetcher.success");
        m.counter("fetcher.success");
        m.counter("fetcher.success", 5);
        assertThat(m.counterValue("fetcher.success")).isEqualTo(7);
    }

    @Test
    void counter_unknown_returns_zero() {
        InMemoryMetrics m = new InMemoryMetrics();
        assertThat(m.counterValue("never-set")).isZero();
    }

    @Test
    void gauge_uses_latest_wins_semantics() {
        InMemoryMetrics m = new InMemoryMetrics();
        m.gauge("frontier.depth", 100);
        m.gauge("frontier.depth", 250);
        assertThat(m.gaugeValue("frontier.depth")).isEqualTo(250.0);
    }

    @Test
    void timer_records_observations_and_computes_percentiles() {
        InMemoryMetrics m = new InMemoryMetrics();
        for (long ns = 1_000_000; ns <= 100_000_000; ns += 1_000_000) {
            m.timer("fetcher.duration", ns);
        }
        Optional<InMemoryMetrics.TimerSnapshot> snap =
            m.timerSnapshot("fetcher.duration");
        assertThat(snap).isPresent();
        assertThat(snap.get().count()).isEqualTo(100);
        assertThat(snap.get().p50Nanos()).isCloseTo(50_000_000L,
            org.assertj.core.data.Offset.offset(2_000_000L));
        assertThat(snap.get().p99Nanos()).isGreaterThanOrEqualTo(snap.get().p50Nanos());
        assertThat(snap.get().maxNanos()).isEqualTo(100_000_000L);
    }

    @Test
    void timer_unknown_returns_empty_snapshot() {
        InMemoryMetrics m = new InMemoryMetrics();
        assertThat(m.timerSnapshot("nope")).isEmpty();
    }

    @Test
    void prometheus_format_includes_counters_gauges_and_timers() {
        InMemoryMetrics m = new InMemoryMetrics();
        m.counter("fetcher.success", 100);
        m.gauge("frontier.depth", 42);
        m.timer("fetcher.duration", 5_000_000);

        String output = m.renderPrometheus();
        assertThat(output).contains("fetcher_success 100");
        assertThat(output).contains("frontier_depth 42");
        assertThat(output).contains("fetcher_duration_seconds_count 1");
        assertThat(output).contains("# TYPE");
    }

    @Test
    void noop_metrics_does_nothing() {
        Metrics.NOOP.counter("a");
        Metrics.NOOP.gauge("b", 42);
        Metrics.NOOP.timer("c", 5_000);
        // Did not throw; nothing else to assert.
    }

    @Test
    void timer_reservoir_wraps_when_full() {
        InMemoryMetrics m = new InMemoryMetrics(64);
        for (int i = 0; i < 1000; i++) {
            m.timer("wrap", i * 1_000_000L);
        }
        InMemoryMetrics.TimerSnapshot snap = m.timerSnapshot("wrap").orElseThrow();
        // Total count records all 1000 observations.
        assertThat(snap.count()).isEqualTo(1000);
        // Reservoir holds only the most recent 64; max should be from
        // those late-arriving samples, not the early ones.
        assertThat(snap.maxNanos()).isGreaterThanOrEqualTo(900_000_000L);
    }

    @Test
    void all_counters_returns_snapshot() {
        InMemoryMetrics m = new InMemoryMetrics();
        m.counter("a");
        m.counter("b", 5);
        var all = m.allCounters();
        assertThat(all).containsEntry("a", 1L);
        assertThat(all).containsEntry("b", 5L);
    }
}
