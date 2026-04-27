package com.hkg.crawler.coordinator.forwarding;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.PriorityClass;
import com.hkg.crawler.coordinator.AgentId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class BatchingForwardingQueueTest {

    private static final AgentId SELF   = AgentId.of("self");
    private static final AgentId TARGET = AgentId.of("target");

    private TestClock clock;
    private InMemoryCrossAgentForwarder forwarder;
    private BatchingForwardingQueue queue;
    private List<ForwardingBatch> deadLetters;

    @BeforeEach
    void setUp() {
        clock = new TestClock(Instant.parse("2026-04-27T12:00:00Z"));
        forwarder = new InMemoryCrossAgentForwarder();
        deadLetters = java.util.Collections.synchronizedList(new ArrayList<>());
        queue = new BatchingForwardingQueue(
            SELF, forwarder,
            5,                            // maxBatchSize
            Duration.ofMillis(50),
            2,                            // maxRetries
            (target, batch) -> deadLetters.add(batch),
            clock);
    }

    private CanonicalUrl url(String s) { return CanonicalUrl.of(s); }

    /** Poll-loop with timeout — small replacement for Awaitility. */
    private static void waitUntil(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void batches_size_5_emitted_when_buffer_fills() {
        for (int i = 0; i < 5; i++) {
            queue.enqueue(TARGET, url("http://example.com/" + i),
                PriorityClass.HIGH_OPIC, null);
        }
        waitUntil(() -> forwarder.inboxOf(TARGET).size() == 1, Duration.ofSeconds(2));
        assertThat(forwarder.inboxOf(TARGET)).hasSize(1);
        assertThat(forwarder.inboxOf(TARGET).get(0).size()).isEqualTo(5);
    }

    @Test
    void age_based_flush_emits_partial_batch() {
        queue.enqueue(TARGET, url("http://a.com/"), PriorityClass.HIGH_OPIC, null);
        queue.enqueue(TARGET, url("http://b.com/"), PriorityClass.HIGH_OPIC, null);
        clock.advance(Duration.ofMillis(100));
        queue.flushDue();
        waitUntil(() -> forwarder.inboxOf(TARGET).size() == 1, Duration.ofSeconds(2));
        assertThat(forwarder.inboxOf(TARGET).get(0).size()).isEqualTo(2);
    }

    @Test
    void flushAll_emits_pending_regardless_of_age() {
        queue.enqueue(TARGET, url("http://a.com/"), PriorityClass.HIGH_OPIC, null);
        queue.flushAll();
        waitUntil(() -> forwarder.inboxOf(TARGET).size() == 1, Duration.ofSeconds(2));
        assertThat(forwarder.inboxOf(TARGET)).hasSize(1);
    }

    @Test
    void distinct_targets_have_independent_buffers() {
        AgentId t2 = AgentId.of("target-2");
        for (int i = 0; i < 3; i++) {
            queue.enqueue(TARGET, url("http://a.com/" + i), PriorityClass.HIGH_OPIC, null);
            queue.enqueue(t2, url("http://b.com/" + i), PriorityClass.HIGH_OPIC, null);
        }
        assertThat(queue.pendingCount(TARGET)).isEqualTo(3);
        assertThat(queue.pendingCount(t2)).isEqualTo(3);

        queue.flushAll();
        waitUntil(() ->
            forwarder.inboxOf(TARGET).size() == 1 && forwarder.inboxOf(t2).size() == 1,
            Duration.ofSeconds(2));
    }

    @Test
    void retries_on_failure_then_succeeds() {
        forwarder.injectFailures(TARGET, 1);   // first send fails
        for (int i = 0; i < 5; i++) {
            queue.enqueue(TARGET, url("http://a.com/" + i),
                PriorityClass.HIGH_OPIC, null);
        }
        // Drive flushAll repeatedly to advance the retry path.
        waitUntil(() -> {
            queue.flushAll();
            return forwarder.inboxOf(TARGET).size() == 1;
        }, Duration.ofSeconds(3));
        assertThat(forwarder.inboxOf(TARGET)).hasSize(1);
        assertThat(deadLetters).isEmpty();
    }

    @Test
    void exceeds_maxRetries_triggers_dead_letter() {
        forwarder.injectFailures(TARGET, 100);   // always fail
        for (int i = 0; i < 5; i++) {
            queue.enqueue(TARGET, url("http://a.com/" + i),
                PriorityClass.HIGH_OPIC, null);
        }
        waitUntil(() -> {
            queue.flushAll();
            return !deadLetters.isEmpty();
        }, Duration.ofSeconds(5));
        assertThat(deadLetters).isNotEmpty();
    }

    @Test
    void successful_send_resets_retry_counter() {
        forwarder.injectFailures(TARGET, 1);
        for (int i = 0; i < 5; i++) {
            queue.enqueue(TARGET, url("http://a.com/" + i),
                PriorityClass.HIGH_OPIC, null);
        }
        waitUntil(() -> {
            queue.flushAll();
            return forwarder.inboxOf(TARGET).size() == 1;
        }, Duration.ofSeconds(3));
        waitUntil(() -> queue.retryCount(TARGET) == 0, Duration.ofSeconds(2));
        assertThat(queue.retryCount(TARGET)).isZero();
    }

    @Test
    void counter_metric_for_in_memory_forwarder() {
        AtomicInteger sends = new AtomicInteger();
        InMemoryCrossAgentForwarder counting = new InMemoryCrossAgentForwarder() {
            @Override
            public java.util.concurrent.CompletableFuture<Void> send(ForwardingBatch batch) {
                sends.incrementAndGet();
                return super.send(batch);
            }
        };
        BatchingForwardingQueue q = new BatchingForwardingQueue(
            SELF, counting, 3, Duration.ofMillis(50), 1,
            (t, b) -> {}, clock);
        for (int i = 0; i < 9; i++) {
            q.enqueue(TARGET, url("http://x.com/" + i), PriorityClass.HIGH_OPIC, null);
        }
        waitUntil(() -> sends.get() == 3, Duration.ofSeconds(2));
        assertThat(sends.get()).isEqualTo(3);
    }

    // -------- helpers -----------------------------------------------

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
