package com.hkg.crawler.common.observability;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * In-process {@link Metrics} backend with bounded per-name reservoirs for
 * timer percentile computation. Production deployments swap in a
 * Prometheus-backed implementation; tests use this directly.
 *
 * <p>Timer reservoir uses a simple ring buffer of the most recent N
 * observations (default 1024). Sufficient for p50/p99/max within the
 * observation window; not a full t-digest but adequate for tests and
 * the single-process simulator.
 */
public final class InMemoryMetrics implements Metrics {

    public static final int DEFAULT_TIMER_RESERVOIR_SIZE = 1024;

    private final ConcurrentHashMap<String, AtomicLong>  counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoubleAdder> gauges   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimerReservoir> timers = new ConcurrentHashMap<>();
    private final int timerReservoirSize;

    public InMemoryMetrics() {
        this(DEFAULT_TIMER_RESERVOIR_SIZE);
    }

    public InMemoryMetrics(int timerReservoirSize) {
        this.timerReservoirSize = timerReservoirSize;
    }

    @Override
    public void counter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public void gauge(String name, double value) {
        gauges.compute(name, (k, existing) -> {
            DoubleAdder a = (existing != null) ? existing : new DoubleAdder();
            // latest-wins semantics — replace
            a.reset();
            a.add(value);
            return a;
        });
    }

    @Override
    public void timer(String name, long nanos) {
        timers.computeIfAbsent(name, k -> new TimerReservoir(timerReservoirSize))
            .record(nanos);
    }

    public long counterValue(String name) {
        AtomicLong c = counters.get(name);
        return c == null ? 0 : c.get();
    }

    public double gaugeValue(String name) {
        DoubleAdder a = gauges.get(name);
        return a == null ? 0.0 : a.sum();
    }

    public Optional<TimerSnapshot> timerSnapshot(String name) {
        TimerReservoir r = timers.get(name);
        return r == null ? Optional.empty() : Optional.of(r.snapshot());
    }

    public Map<String, Long> allCounters() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return Map.copyOf(out);
    }

    /**
     * Render all metrics in Prometheus text format for /metrics scraping.
     * Production replaces this with a Micrometer + PrometheusMeterRegistry
     * pair; this is the "we run without external deps" fallback.
     */
    public String renderPrometheus() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((name, v) -> {
            sb.append("# TYPE ").append(metricName(name)).append(" counter\n");
            sb.append(metricName(name)).append(' ').append(v.get()).append('\n');
        });
        gauges.forEach((name, v) -> {
            sb.append("# TYPE ").append(metricName(name)).append(" gauge\n");
            sb.append(metricName(name)).append(' ').append(v.sum()).append('\n');
        });
        timers.forEach((name, r) -> {
            TimerSnapshot snap = r.snapshot();
            String base = metricName(name);
            sb.append("# TYPE ").append(base).append("_seconds summary\n");
            sb.append(base).append("_seconds{quantile=\"0.5\"} ")
              .append(snap.p50Nanos / 1e9).append('\n');
            sb.append(base).append("_seconds{quantile=\"0.99\"} ")
              .append(snap.p99Nanos / 1e9).append('\n');
            sb.append(base).append("_seconds_count ").append(snap.count).append('\n');
        });
        return sb.toString();
    }

    private static String metricName(String s) {
        return s.replace('.', '_').replace('-', '_');
    }

    /** Snapshot of a timer's percentiles at the moment of computation. */
    public record TimerSnapshot(long count, long p50Nanos, long p99Nanos, long maxNanos) {}

    /** Bounded ring buffer for timer observations. */
    private static final class TimerReservoir {
        private final long[] samples;
        private final int size;
        private int writeIndex = 0;
        private long totalCount = 0;

        TimerReservoir(int size) {
            this.size = size;
            this.samples = new long[size];
        }

        synchronized void record(long nanos) {
            samples[writeIndex] = nanos;
            writeIndex = (writeIndex + 1) % size;
            totalCount++;
        }

        synchronized TimerSnapshot snapshot() {
            int filled = (int) Math.min(totalCount, size);
            if (filled == 0) return new TimerSnapshot(0, 0, 0, 0);
            long[] copy = Arrays.copyOf(samples, filled);
            Arrays.sort(copy);
            long p50 = copy[(int) (copy.length * 0.50)];
            long p99 = copy[Math.min(copy.length - 1, (int) (copy.length * 0.99))];
            long max = copy[copy.length - 1];
            return new TimerSnapshot(totalCount, p50, p99, max);
        }
    }
}
