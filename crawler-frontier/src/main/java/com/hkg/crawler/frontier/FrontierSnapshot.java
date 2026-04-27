package com.hkg.crawler.frontier;

import java.io.Serializable;
import java.util.List;

/**
 * Wire-stable serializable snapshot of a Frontier's complete state.
 *
 * <p>Designed to be round-trippable across JVM restarts: contains only
 * primitives, strings, and lists of records — no typed domain objects
 * (Host, CanonicalUrl, PriorityClass) — so deserialization can re-run the
 * full validation pipeline via {@code Host.of(...)} and
 * {@code CanonicalUrl.of(...)} on restore.
 *
 * <p>Persistence format: {@link java.io.ObjectOutputStream} bytes,
 * stored as a single value in a {@code FrontierSnapshotStore}.
 *
 * <p>Cadence: 10-second checkpointing (per the blog's §3 Frontier
 * Service recommendation). On crash, up to 10s of scheduling work is
 * lost — but the URLs themselves are durable and the heap is
 * reconstructed from {@link HostSnapshot#nextFetchTimeEpochMs}.
 */
public record FrontierSnapshot(
    int formatVersion,
    long takenAtEpochMs,
    List<HostSnapshot> hosts,
    List<BackQueueEntry> backQueueUrls,
    List<FrontQueueEntry> frontQueueUrls
) implements Serializable {

    public static final int CURRENT_FORMAT_VERSION = 1;

    public FrontierSnapshot {
        hosts = List.copyOf(hosts);
        backQueueUrls = List.copyOf(backQueueUrls);
        frontQueueUrls = List.copyOf(frontQueueUrls);
    }

    /** Per-host politeness state; restored into a fresh {@code HostState} instance. */
    public record HostSnapshot(
        String hostName,
        long   nextFetchTimeEpochMs,
        long   crawlDelayMs,
        double backoffFactor,
        int    consecutiveErrors,
        long   lastSuccessEpochMs,
        int    backQueueDepth,
        boolean quarantined
    ) implements Serializable {}

    /** A URL waiting in a host's back queue, ready to be claimed. */
    public record BackQueueEntry(
        String hostName,
        String url,
        String priorityClass,
        long   discoveredAtEpochMs
    ) implements Serializable {}

    /** A URL waiting in a front queue, not yet routed to a back queue. */
    public record FrontQueueEntry(
        String priorityClass,
        String url,
        long   discoveredAtEpochMs
    ) implements Serializable {}
}
