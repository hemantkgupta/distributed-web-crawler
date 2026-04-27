package com.hkg.crawler.bench;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Simple load harness for ad-hoc throughput + latency measurement
 * against any crawler service. Not a JMH-quality microbenchmark — the
 * goal is the macroscopic numbers (ops/sec, p99 latency) that matter
 * for deciding "did this checkpoint regress."
 *
 * <p>Usage:
 *
 * <pre>
 *   BenchResult r = BenchRunner.builder("frontier-claim")
 *       .threads(8)
 *       .totalOperations(100_000)
 *       .warmup(Duration.ofSeconds(2))
 *       .run(opIndex -&gt; frontier.claimNext(Instant.now()));
 *   System.out.println(r.summary());
 * </pre>
 */
public final class BenchRunner {

    private final String name;
    private final int threads;
    private final int totalOperations;
    private final Duration warmup;

    private BenchRunner(String name, int threads, int totalOperations, Duration warmup) {
        this.name = name;
        this.threads = threads;
        this.totalOperations = totalOperations;
        this.warmup = warmup;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private int threads = Runtime.getRuntime().availableProcessors();
        private int totalOperations = 10_000;
        private Duration warmup = Duration.ZERO;

        private Builder(String name) { this.name = name; }

        public Builder threads(int threads) {
            if (threads < 1) throw new IllegalArgumentException("threads must be ≥ 1");
            this.threads = threads;
            return this;
        }

        public Builder totalOperations(int n) {
            if (n < 1) throw new IllegalArgumentException("totalOperations must be ≥ 1");
            this.totalOperations = n;
            return this;
        }

        public Builder warmup(Duration d) {
            if (d.isNegative()) throw new IllegalArgumentException("warmup must be ≥ 0");
            this.warmup = d;
            return this;
        }

        public BenchResult run(IntConsumer operation) {
            return new BenchRunner(name, threads, totalOperations, warmup).execute(operation);
        }
    }

    // ---- execution -----------------------------------------------------

    private BenchResult execute(IntConsumer operation) {
        // Warmup phase: run for `warmup` duration without recording.
        if (!warmup.isZero()) {
            warmupRun(operation);
        }

        long[] latencies = new long[totalOperations];
        AtomicInteger errorCount = new AtomicInteger();
        AtomicInteger nextOp = new AtomicInteger();

        Instant start = Instant.now();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    int idx;
                    while ((idx = nextOp.getAndIncrement()) < totalOperations) {
                        long t0 = System.nanoTime();
                        try { operation.accept(idx); }
                        catch (Throwable e) { errorCount.incrementAndGet(); }
                        latencies[idx] = System.nanoTime() - t0;
                    }
                });
            }
            for (Future<?> f : futures) f.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            pool.shutdown();
            try { pool.awaitTermination(30, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        Duration wall = Duration.between(start, Instant.now());

        Arrays.sort(latencies);
        double throughput = totalOperations / Math.max(0.001, wall.toMillis() / 1000.0);
        return new BenchResult(
            name,
            totalOperations,
            errorCount.get(),
            wall,
            throughput,
            latencies[(int) (latencies.length * 0.50)],
            latencies[(int) (latencies.length * 0.99)],
            latencies[latencies.length - 1]
        );
    }

    private void warmupRun(IntConsumer operation) {
        long deadline = System.nanoTime() + warmup.toNanos();
        AtomicLong opCount = new AtomicLong();
        Random r = new Random(0);
        while (System.nanoTime() < deadline) {
            try { operation.accept(r.nextInt(Integer.MAX_VALUE)); }
            catch (Throwable ignored) { /* swallow during warmup */ }
            opCount.incrementAndGet();
        }
    }
}
