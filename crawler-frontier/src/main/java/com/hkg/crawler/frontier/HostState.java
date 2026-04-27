package com.hkg.crawler.frontier;

import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;

import java.time.Duration;
import java.time.Instant;

/**
 * Mutable per-host politeness and bookkeeping state attached to each
 * back queue.
 *
 * <p>The {@link #nextFetchTime} field is the load-bearing one — it drives
 * the Ready-Host Min-Heap's ordering. After each fetch verdict, this state
 * is updated by {@link #recordVerdict} and the heap is re-keyed.
 *
 * <p>Backoff factor uses an exponential rule on errors and a gradual
 * recovery on success:
 * <ul>
 *   <li>2.0× per consecutive backoff signal (5xx, 429, network, timeout),
 *       capped at {@link #MAX_BACKOFF}</li>
 *   <li>0.95× per success, floor 1.0</li>
 * </ul>
 *
 * <p>Not thread-safe. The {@code Frontier} that owns this state is
 * responsible for synchronization.
 */
public final class HostState {

    public static final double  MIN_BACKOFF = 1.0;
    public static final double  MAX_BACKOFF = 64.0;
    public static final double  ERROR_MULTIPLIER  = 2.0;
    public static final double  SUCCESS_DIVISOR   = 0.95;
    public static final Duration DEFAULT_CRAWL_DELAY = Duration.ofSeconds(1);

    private final Host host;

    private Instant   nextFetchTime;
    private Duration  crawlDelay;
    private double    backoffFactor;
    private int       consecutiveErrors;
    private Instant   lastSuccessTime;
    private int       backQueueDepth;
    private boolean   quarantined;

    public HostState(Host host, Instant initialEligibleAt) {
        this.host              = host;
        this.nextFetchTime     = initialEligibleAt;
        this.crawlDelay        = DEFAULT_CRAWL_DELAY;
        this.backoffFactor     = MIN_BACKOFF;
        this.consecutiveErrors = 0;
        this.lastSuccessTime   = Instant.EPOCH;
        this.backQueueDepth    = 0;
        this.quarantined       = false;
    }

    public Host host()                 { return host; }
    public Instant nextFetchTime()     { return nextFetchTime; }
    public Duration crawlDelay()       { return crawlDelay; }
    public double backoffFactor()      { return backoffFactor; }
    public int consecutiveErrors()     { return consecutiveErrors; }
    public Instant lastSuccessTime()   { return lastSuccessTime; }
    public int backQueueDepth()        { return backQueueDepth; }
    public boolean isQuarantined()     { return quarantined; }

    public void setCrawlDelay(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("crawlDelay must be positive");
        }
        this.crawlDelay = d;
    }

    public void setQuarantined(boolean q) { this.quarantined = q; }

    public void incrementBackQueueDepth() { backQueueDepth++; }
    public void decrementBackQueueDepth() {
        if (backQueueDepth <= 0) {
            throw new IllegalStateException("backQueueDepth would go negative for " + host);
        }
        backQueueDepth--;
    }

    /**
     * Compute the effective fetch interval for this host. This is
     * {@code crawlDelay × backoffFactor}, used to advance
     * {@link #nextFetchTime} after a claim.
     */
    public Duration effectiveDelay() {
        long nanos = (long) (crawlDelay.toNanos() * backoffFactor);
        return Duration.ofNanos(nanos);
    }

    /**
     * Mark this host as having just been claimed for fetching at
     * {@code now}. Advances {@link #nextFetchTime} so a follow-up
     * {@code claimNext} doesn't re-pick this host immediately.
     */
    public void recordClaim(Instant now) {
        this.nextFetchTime = now.plus(effectiveDelay());
    }

    /**
     * Package-private restore hook used by {@link InMemoryFrontier#restore}
     * to reconstitute a snapshot's saved backoff factor without driving
     * the verdict path.
     */
    void restoreBackoffFactor(double factor) {
        if (factor < MIN_BACKOFF || factor > MAX_BACKOFF) {
            throw new IllegalArgumentException("backoffFactor out of range: " + factor);
        }
        this.backoffFactor = factor;
    }

    void restoreIncrementConsecutiveErrors() {
        this.consecutiveErrors++;
    }

    /**
     * Update host state based on a fetch verdict. Adjusts
     * {@link #backoffFactor} per the exponential rule, advances
     * {@link #nextFetchTime} to {@code now + effectiveDelay()}, and
     * tracks {@link #consecutiveErrors}.
     */
    public void recordVerdict(FetchOutcome outcome, Instant now) {
        if (outcome.isBackoffSignal()) {
            backoffFactor = Math.min(MAX_BACKOFF, backoffFactor * ERROR_MULTIPLIER);
            consecutiveErrors++;
        } else if (outcome.isSuccess()) {
            backoffFactor = Math.max(MIN_BACKOFF, backoffFactor * SUCCESS_DIVISOR);
            consecutiveErrors = 0;
            lastSuccessTime = now;
        }
        // Other outcomes (4xx, redirect, robots-disallowed) leave backoff unchanged.
        this.nextFetchTime = now.plus(effectiveDelay());
    }
}
