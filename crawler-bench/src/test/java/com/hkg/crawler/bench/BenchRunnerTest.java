package com.hkg.crawler.bench;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchRunnerTest {

    @Test
    void runs_target_op_count_exactly() {
        AtomicInteger counter = new AtomicInteger();
        BenchResult r = BenchRunner.builder("count-ops")
            .threads(4)
            .totalOperations(1000)
            .run(idx -> counter.incrementAndGet());
        assertThat(counter.get()).isEqualTo(1000);
        assertThat(r.totalOperations()).isEqualTo(1000);
        assertThat(r.errorCount()).isZero();
    }

    @Test
    void records_errors_without_failing_run() {
        BenchResult r = BenchRunner.builder("err")
            .threads(2)
            .totalOperations(100)
            .run(idx -> { if (idx % 5 == 0) throw new RuntimeException("fail"); });
        assertThat(r.errorCount()).isEqualTo(20);
        assertThat(r.totalOperations()).isEqualTo(100);
    }

    @Test
    void throughput_is_positive_for_non_trivial_op() {
        BenchResult r = BenchRunner.builder("throughput")
            .threads(4)
            .totalOperations(10_000)
            .run(idx -> { /* no-op */ });
        assertThat(r.throughputPerSecond()).isPositive();
        assertThat(r.wallTime().toMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void latency_percentiles_are_ordered() {
        BenchResult r = BenchRunner.builder("latencies")
            .threads(2)
            .totalOperations(1000)
            .run(idx -> { /* fast */ });
        assertThat(r.latencyP50Ns()).isLessThanOrEqualTo(r.latencyP99Ns());
        assertThat(r.latencyP99Ns()).isLessThanOrEqualTo(r.latencyMaxNs());
    }

    @Test
    void warmup_does_not_count_operations() {
        AtomicInteger duringRun = new AtomicInteger();
        BenchResult r = BenchRunner.builder("warmup")
            .threads(2)
            .totalOperations(100)
            .warmup(Duration.ofMillis(50))
            .run(idx -> duringRun.incrementAndGet());
        // duringRun gets incremented during both warmup AND measurement, but
        // the recorded totalOperations only reflects the measured count.
        assertThat(r.totalOperations()).isEqualTo(100);
        assertThat(duringRun.get()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void summary_string_contains_key_numbers() {
        BenchResult r = BenchRunner.builder("summary")
            .threads(1)
            .totalOperations(50)
            .run(idx -> {});
        String s = r.summary();
        assertThat(s).contains("summary");
        assertThat(s).contains("50 ops");
        assertThat(s).contains("ops/s");
        assertThat(s).contains("p50=");
        assertThat(s).contains("p99=");
    }

    @Test
    void rejects_invalid_builder_inputs() {
        assertThatThrownBy(() -> BenchRunner.builder("x").threads(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchRunner.builder("x").totalOperations(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchRunner.builder("x").warmup(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
