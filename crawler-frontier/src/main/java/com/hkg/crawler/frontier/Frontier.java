package com.hkg.crawler.frontier;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;

import java.time.Instant;
import java.util.Optional;

/**
 * The URL Frontier — a Mercator-style two-tier scheduling system that
 * jointly optimizes discovery order, host politeness, and worker
 * utilization.
 *
 * <p>Two tiers:
 * <ul>
 *   <li><b>Front queues</b>: priority-class FIFOs, fed by callers via
 *       {@link #enqueue}.</li>
 *   <li><b>Back queues</b>: per-host FIFOs, fed by the Back-Queue Selector
 *       which pulls from the front queues weighted by class.</li>
 * </ul>
 *
 * <p>The Ready-Host Min-Heap, keyed on each back queue's
 * {@code next_fetch_time}, makes politeness non-blocking: while host A is
 * cooling down, the heap surfaces hosts B, C, D in sequence so workers
 * never idle while work exists.
 *
 * <p>Implementations may be in-memory (single-shard, no persistence) or
 * RocksDB-backed (durable across restart). Both honor the same contract.
 */
public interface Frontier {

    /**
     * Enqueue a URL into its host's back queue (creating one if needed).
     * If the URL routes to a host whose back queue is currently empty,
     * the URL becomes the new head and the host re-enters the heap with
     * {@code next_fetch_time = now} (immediately eligible).
     */
    void enqueue(FrontierUrl url);

    /**
     * Pop the soonest-ready URL. Returns empty if no host is ready
     * <em>at {@code now}</em> — the caller should sleep until at least
     * the {@link FrontierStats#earliestReadyTime} and try again.
     *
     * @param now the current wall-clock time
     */
    Optional<ClaimedUrl> claimNext(Instant now);

    /**
     * Report the verdict for a previously-claimed URL. Updates the host's
     * politeness state (backoff factor, consecutive errors) and re-keys
     * the host in the heap.
     */
    void reportVerdict(CanonicalUrl url, FetchOutcome outcome, Instant now);

    /** Operator-driven host quarantine; removes the host from scheduling. */
    void quarantineHost(Host host);

    /** Restore a previously-quarantined host. */
    void releaseHost(Host host);

    /** Snapshot of internal state for observability. */
    FrontierStats stats();
}
