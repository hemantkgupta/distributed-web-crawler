package com.hkg.crawler.coordinator.forwarding;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.PriorityClass;
import com.hkg.crawler.coordinator.AgentId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-target batching queue that turns a stream of single-URL
 * forwarding requests into bulk {@link ForwardingBatch}es.
 *
 * <p>Flush policy: emit a batch when **either** of these is true:
 * <ul>
 *   <li>The accumulated URL count for a target reaches {@code maxBatchSize}
 *       (default 100).</li>
 *   <li>The oldest queued URL is older than {@code maxBatchAge}
 *       (default 50ms).</li>
 * </ul>
 *
 * <p>Failure handling on {@link CrossAgentForwarder#send}: per-target
 * retry counter increments; after {@code maxRetries} the batch is
 * routed to the dead-letter callback (callers wire to a Kafka DLQ in
 * production). Successful batches reset the counter.
 *
 * <p>Single-threaded internal locking by per-target lock; the public
 * API is callable from many threads.
 */
public final class BatchingForwardingQueue {

    public static final int      DEFAULT_MAX_BATCH_SIZE = 100;
    public static final Duration DEFAULT_MAX_BATCH_AGE  = Duration.ofMillis(50);
    public static final int      DEFAULT_MAX_RETRIES    = 3;

    private final AgentId selfAgent;
    private final CrossAgentForwarder forwarder;
    private final int maxBatchSize;
    private final Duration maxBatchAge;
    private final int maxRetries;
    private final DeadLetterSink deadLetterSink;
    private final Clock clock;

    private final ConcurrentHashMap<AgentId, PerTargetBuffer> buffers = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public BatchingForwardingQueue(AgentId selfAgent, CrossAgentForwarder forwarder) {
        this(selfAgent, forwarder,
            DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_BATCH_AGE, DEFAULT_MAX_RETRIES,
            (target, batch) -> { /* default dead-letter: drop */ },
            Clock.systemUTC());
    }

    public BatchingForwardingQueue(AgentId selfAgent, CrossAgentForwarder forwarder,
                                    int maxBatchSize, Duration maxBatchAge, int maxRetries,
                                    DeadLetterSink deadLetterSink, Clock clock) {
        this.selfAgent = selfAgent;
        this.forwarder = forwarder;
        this.maxBatchSize = maxBatchSize;
        this.maxBatchAge = maxBatchAge;
        this.maxRetries = maxRetries;
        this.deadLetterSink = deadLetterSink;
        this.clock = clock;
    }

    /** Enqueue a URL for forwarding to {@code target}. */
    public void enqueue(AgentId target, CanonicalUrl url, PriorityClass priorityClass,
                         CanonicalUrl discoveredFrom) {
        PerTargetBuffer buf = buffers.computeIfAbsent(target, t -> new PerTargetBuffer());
        Instant now = clock.instant();
        synchronized (buf) {
            buf.entries.add(new ForwardingBatch.UrlEntry(url, priorityClass, discoveredFrom, now));
            if (buf.entries.size() >= maxBatchSize) {
                flushTarget(target, buf, now);
            }
        }
    }

    /**
     * Force-flush all per-target buffers whose oldest entry exceeds
     * {@code maxBatchAge}. Called periodically (e.g., every 10ms) by
     * the runtime, and synchronously on shutdown.
     */
    public void flushDue() {
        Instant now = clock.instant();
        for (var e : buffers.entrySet()) {
            PerTargetBuffer buf = e.getValue();
            synchronized (buf) {
                if (buf.entries.isEmpty()) continue;
                Instant oldest = buf.entries.get(0).discoveredAt();
                if (Duration.between(oldest, now).compareTo(maxBatchAge) >= 0) {
                    flushTarget(e.getKey(), buf, now);
                }
            }
        }
    }

    /** Force-flush every per-target buffer regardless of age. */
    public void flushAll() {
        Instant now = clock.instant();
        for (var e : buffers.entrySet()) {
            PerTargetBuffer buf = e.getValue();
            synchronized (buf) {
                if (!buf.entries.isEmpty()) flushTarget(e.getKey(), buf, now);
            }
        }
    }

    public int pendingCount(AgentId target) {
        PerTargetBuffer buf = buffers.get(target);
        if (buf == null) return 0;
        synchronized (buf) { return buf.entries.size(); }
    }

    public int retryCount(AgentId target) {
        PerTargetBuffer buf = buffers.get(target);
        if (buf == null) return 0;
        synchronized (buf) { return buf.consecutiveRetries; }
    }

    // ---- internals -----------------------------------------------------

    private void flushTarget(AgentId target, PerTargetBuffer buf, Instant now) {
        List<ForwardingBatch.UrlEntry> snapshot = new ArrayList<>(buf.entries);
        buf.entries.clear();
        ForwardingBatch batch = new ForwardingBatch(
            selfAgent, target, sequence.incrementAndGet(), now, snapshot);

        forwarder.send(batch).whenComplete((unused, ex) -> {
            if (ex == null) {
                synchronized (buf) { buf.consecutiveRetries = 0; }
                return;
            }
            synchronized (buf) {
                buf.consecutiveRetries++;
                if (buf.consecutiveRetries > maxRetries) {
                    buf.consecutiveRetries = 0;
                    deadLetterSink.onDeadLetter(target, batch);
                } else {
                    // Re-enqueue at the front for retry on next flush.
                    buf.entries.addAll(0, batch.urls());
                }
            }
        });
    }

    /** Per-destination buffer holding pending URLs. */
    private static final class PerTargetBuffer {
        final List<ForwardingBatch.UrlEntry> entries = new ArrayList<>();
        int consecutiveRetries = 0;
    }

    /** Sink for batches that exhaust retry budget — Kafka DLQ in production. */
    @FunctionalInterface
    public interface DeadLetterSink {
        void onDeadLetter(AgentId target, ForwardingBatch batch);
    }
}
