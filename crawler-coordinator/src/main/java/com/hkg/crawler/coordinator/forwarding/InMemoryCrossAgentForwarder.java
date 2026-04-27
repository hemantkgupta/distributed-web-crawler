package com.hkg.crawler.coordinator.forwarding;

import com.hkg.crawler.coordinator.AgentId;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * In-process {@link CrossAgentForwarder} for tests and the single-
 * process simulator. Delivers each batch to a per-target-agent inbox;
 * test code reads inboxes to verify forwarding behavior.
 *
 * <p>Optionally fails the next N sends to a target, used by the
 * failure-injection tests that exercise the retry/dead-letter path.
 */
public class InMemoryCrossAgentForwarder implements CrossAgentForwarder {

    private final ConcurrentHashMap<AgentId, ConcurrentLinkedQueue<ForwardingBatch>> inboxes =
        new ConcurrentHashMap<>();

    /** Per-target failure-injection counter; >0 means fail this many sends. */
    private final ConcurrentHashMap<AgentId, AtomicInteger> failuresQueued =
        new ConcurrentHashMap<>();

    /** Optional failure handler callback that decides exception per batch. */
    private final Function<ForwardingBatch, RuntimeException> failureSupplier;

    public InMemoryCrossAgentForwarder() {
        this(b -> new RuntimeException("simulated forwarding failure"));
    }

    public InMemoryCrossAgentForwarder(Function<ForwardingBatch, RuntimeException> failureSupplier) {
        this.failureSupplier = failureSupplier;
    }

    @Override
    public CompletableFuture<Void> send(ForwardingBatch batch) {
        AtomicInteger pendingFailures = failuresQueued.get(batch.targetAgent());
        if (pendingFailures != null && pendingFailures.get() > 0) {
            pendingFailures.decrementAndGet();
            return CompletableFuture.failedFuture(failureSupplier.apply(batch));
        }
        inboxes.computeIfAbsent(batch.targetAgent(), k -> new ConcurrentLinkedQueue<>())
            .offer(batch);
        return CompletableFuture.completedFuture(null);
    }

    /** Snapshot copy of all batches currently queued at {@code target}'s inbox. */
    public List<ForwardingBatch> inboxOf(AgentId target) {
        ConcurrentLinkedQueue<ForwardingBatch> q = inboxes.get(target);
        return q == null ? List.of() : List.copyOf(q);
    }

    /** Drain the target's inbox. */
    public List<ForwardingBatch> drainInboxOf(AgentId target) {
        ConcurrentLinkedQueue<ForwardingBatch> q = inboxes.get(target);
        if (q == null) return List.of();
        java.util.List<ForwardingBatch> out = new java.util.ArrayList<>();
        ForwardingBatch b;
        while ((b = q.poll()) != null) out.add(b);
        return out;
    }

    /** Inject N failures: the next {@code n} sends to {@code target} will fail. */
    public void injectFailures(AgentId target, int n) {
        failuresQueued.computeIfAbsent(target, k -> new AtomicInteger()).addAndGet(n);
    }

    @Override
    public void close() { /* no resources */ }
}
