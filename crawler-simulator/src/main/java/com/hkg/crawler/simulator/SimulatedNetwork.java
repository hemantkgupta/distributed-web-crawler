package com.hkg.crawler.simulator;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

/**
 * Deterministic network model for the simulator. Records every message
 * sent through it (for replay) and supports configurable failure
 * injection (drop, delay, reorder) seeded by a single PRNG so test
 * runs are bit-for-bit reproducible.
 *
 * <p>The simulator's main loop drives this network: each "tick" the
 * network emits the messages that have aged past their delivery delay.
 *
 * @param <M> message type
 */
public final class SimulatedNetwork<M> {

    private final Random random;
    private final double dropProbability;
    private final long minDelayMs;
    private final long maxDelayMs;
    private final Queue<PendingMessage<M>> pending = new LinkedList<>();
    private final List<M> delivered = new java.util.ArrayList<>();
    private final List<M> dropped = new java.util.ArrayList<>();

    public SimulatedNetwork(long seed) {
        this(seed, 0.0, 0, 0);
    }

    public SimulatedNetwork(long seed, double dropProbability,
                             long minDelayMs, long maxDelayMs) {
        if (dropProbability < 0 || dropProbability > 1) {
            throw new IllegalArgumentException("dropProbability must be in [0,1]");
        }
        if (minDelayMs > maxDelayMs) {
            throw new IllegalArgumentException("minDelayMs > maxDelayMs");
        }
        this.random = new Random(seed);
        this.dropProbability = dropProbability;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** Send a message at logical time {@code sentAtMs}. */
    public synchronized void send(M message, long sentAtMs) {
        if (random.nextDouble() < dropProbability) {
            dropped.add(message);
            return;
        }
        long delay = minDelayMs == maxDelayMs
            ? minDelayMs
            : minDelayMs + (long) (random.nextDouble() * (maxDelayMs - minDelayMs));
        pending.offer(new PendingMessage<>(message, sentAtMs + delay));
    }

    /**
     * Deliver messages whose delivery time is &lt;= {@code currentTimeMs}.
     * Returns the messages delivered in this tick (in order).
     */
    public synchronized List<M> tick(long currentTimeMs) {
        List<M> out = new java.util.ArrayList<>();
        for (var it = pending.iterator(); it.hasNext(); ) {
            PendingMessage<M> p = it.next();
            if (p.deliveryTimeMs <= currentTimeMs) {
                out.add(p.message);
                delivered.add(p.message);
                it.remove();
            }
        }
        return out;
    }

    public synchronized int pendingCount()    { return pending.size(); }
    public synchronized int deliveredCount()  { return delivered.size(); }
    public synchronized int droppedCount()    { return dropped.size(); }
    public synchronized List<M> deliveredLog(){ return List.copyOf(delivered); }

    private static final class PendingMessage<M> {
        final M message;
        final long deliveryTimeMs;
        PendingMessage(M message, long deliveryTimeMs) {
            this.message = message;
            this.deliveryTimeMs = deliveryTimeMs;
        }
    }
}
