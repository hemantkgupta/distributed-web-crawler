package com.hkg.crawler.common.observability;

/**
 * Lightweight metrics SPI used across all crawler services. Production
 * deployments wire a Micrometer/Prometheus implementation; tests and the
 * default in-process setup use {@link InMemoryMetrics}.
 *
 * <p>Per blog §11+§27: the metric set that matters at crawler scale is
 * deliberately small — fetch success rate, fetch latency p50/p99, frontier
 * depth, host backoff distribution, dedup hit rate, robots cache hit rate,
 * DNS p99. We don't try to be Micrometer; we just need stable counters
 * and timers across services.
 */
public interface Metrics {

    /** Increment a counter by 1. */
    default void counter(String name) { counter(name, 1); }

    /** Increment a counter by {@code delta}. */
    void counter(String name, long delta);

    /** Set a gauge to {@code value} (latest-wins semantics). */
    void gauge(String name, double value);

    /** Record an observation in nanoseconds; backend computes p50/p99. */
    void timer(String name, long nanos);

    /** No-op metrics — the default for services that don't wire up a backend. */
    Metrics NOOP = new Metrics() {
        @Override public void counter(String name, long delta) {}
        @Override public void gauge(String name, double value)   {}
        @Override public void timer(String name, long nanos)     {}
    };
}
