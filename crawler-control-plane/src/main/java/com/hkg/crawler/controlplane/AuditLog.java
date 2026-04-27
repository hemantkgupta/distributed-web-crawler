package com.hkg.crawler.controlplane;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only hash-chained audit log. Every operator action is
 * recorded with a SHA-256 chain hash linking back to the previous
 * event; any mid-chain tampering is detectable by re-walking the
 * chain.
 *
 * <p>Production: events are flushed to ClickHouse for analytics +
 * cross-region replication. The in-memory implementation suffices
 * for tests and the single-process simulator.
 */
public final class AuditLog {

    private final List<AuditEvent> events = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;

    public AuditLog() { this(Clock.systemUTC()); }

    public AuditLog(Clock clock) {
        this.clock = clock;
    }

    /**
     * Record a new audit event. Returns the event with its computed
     * chain hash.
     */
    public synchronized AuditEvent record(String actor, String actionType,
                                           String targetUrl, String targetHost,
                                           Map<String, String> details) {
        return record(actor, actionType, targetUrl, targetHost, details,
            UUID.randomUUID().toString());
    }

    public synchronized AuditEvent record(String actor, String actionType,
                                           String targetUrl, String targetHost,
                                           Map<String, String> details,
                                           String requestId) {
        long seq = sequence.incrementAndGet();
        Instant now = clock.instant();
        String prevHash = events.isEmpty()
            ? AuditEvent.GENESIS_HASH
            : events.get(events.size() - 1).chainHash();
        AuditEvent event = AuditEvent.createWithChain(
            seq, now, actor, actionType, targetUrl, targetHost,
            details == null ? Map.of() : details, requestId, prevHash);
        events.add(event);
        return event;
    }

    public synchronized List<AuditEvent> snapshot() {
        return List.copyOf(events);
    }

    public synchronized int size() { return events.size(); }

    /**
     * Walk the chain and verify that every event's hash matches its
     * recomputed content + previous hash. Returns the index of the
     * first event whose hash fails to verify (i.e., the tamper
     * point), or {@code -1} if the chain is intact.
     */
    public synchronized int findFirstTamperedEvent() {
        String expectedPrev = AuditEvent.GENESIS_HASH;
        for (int i = 0; i < events.size(); i++) {
            AuditEvent e = events.get(i);
            if (!e.previousChainHash().equals(expectedPrev)) return i;
            if (!e.verifyChainHash()) return i;
            expectedPrev = e.chainHash();
        }
        return -1;
    }
}
