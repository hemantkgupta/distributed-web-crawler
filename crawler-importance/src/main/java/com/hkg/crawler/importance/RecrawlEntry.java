package com.hkg.crawler.importance;

import com.hkg.crawler.common.CanonicalUrl;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-URL state tracked by the recrawl scheduler.
 *
 * <p>The {@code estimatedChangeRate} (λ̂) is the Cho-Garcia-Molina
 * change-rate estimate, smoothed via EWMA on observed fetch outcomes.
 *
 * <p>Mutable; thread-safety is provided by {@link RecrawlScheduler}'s
 * {@code synchronized} on its containing map operations.
 */
public final class RecrawlEntry {

    /** Hard floor and cap on recrawl interval, blog §10 Problem 5. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(15);
    public static final Duration MAX_INTERVAL = Duration.ofDays(30);

    private final CanonicalUrl url;
    private Instant  lastFetchedAt;
    private Instant  lastChangedAt;
    private Instant  nextRecrawlTime;
    private Duration currentInterval;
    private double   estimatedChangeRate;     // λ̂ (changes per day)
    private int      observedFetches;
    private int      observedChanges;
    private int      consecutiveUnchanged;

    public RecrawlEntry(CanonicalUrl url, Instant firstObservedAt, Duration initialInterval) {
        this.url = url;
        this.lastFetchedAt = firstObservedAt;
        this.lastChangedAt = firstObservedAt;
        this.currentInterval = clamp(initialInterval);
        this.nextRecrawlTime = firstObservedAt.plus(currentInterval);
        this.estimatedChangeRate = 1.0;
        this.observedFetches = 0;
        this.observedChanges = 0;
        this.consecutiveUnchanged = 0;
    }

    public CanonicalUrl url()                  { return url; }
    public Instant      lastFetchedAt()        { return lastFetchedAt; }
    public Instant      lastChangedAt()        { return lastChangedAt; }
    public Instant      nextRecrawlTime()      { return nextRecrawlTime; }
    public Duration     currentInterval()      { return currentInterval; }
    public double       estimatedChangeRate()  { return estimatedChangeRate; }
    public int          observedFetches()      { return observedFetches; }
    public int          observedChanges()      { return observedChanges; }
    public int          consecutiveUnchanged() { return consecutiveUnchanged; }

    /**
     * Apply a fetch outcome: update {@code λ̂}, advance interval,
     * recompute {@code nextRecrawlTime}.
     *
     * <p>Backoff rule (per blog §10 Problem 5 — exponential with cap):
     * <ul>
     *   <li>Content changed → halve interval (anchor toward MIN_INTERVAL).</li>
     *   <li>Content unchanged → double interval (extend toward MAX_INTERVAL).</li>
     * </ul>
     */
    public void recordFetch(boolean contentChanged, Instant now) {
        observedFetches++;
        lastFetchedAt = now;

        if (contentChanged) {
            observedChanges++;
            lastChangedAt = now;
            consecutiveUnchanged = 0;
            currentInterval = clamp(halve(currentInterval));
        } else {
            consecutiveUnchanged++;
            currentInterval = clamp(doubled(currentInterval));
        }

        // EWMA-smoothed change rate: rate per day
        double observedRate = (double) observedChanges
            / Math.max(1, durationDays(durationSinceFirstFetch(now)));
        estimatedChangeRate = 0.7 * estimatedChangeRate + 0.3 * observedRate;

        nextRecrawlTime = now.plus(currentInterval);
    }

    /**
     * Recrawl priority — combines change-rate estimate with optional
     * importance signal. Larger value = higher priority.
     *
     * <p>Per blog §10:
     * {@code priority = expected_information_gain(λ̂) × value(URL)}.
     * We use {@code log(1 + λ̂)} as a damped information-gain proxy.
     */
    public double priorityWithImportance(double importance) {
        double infoGain = Math.log1p(estimatedChangeRate);
        return infoGain * importance;
    }

    // ---- helpers -------------------------------------------------------

    private static Duration halve(Duration d) { return d.dividedBy(2); }
    private static Duration doubled(Duration d) { return d.multipliedBy(2); }

    private static Duration clamp(Duration d) {
        if (d.compareTo(MIN_INTERVAL) < 0) return MIN_INTERVAL;
        if (d.compareTo(MAX_INTERVAL) > 0) return MAX_INTERVAL;
        return d;
    }

    private Duration durationSinceFirstFetch(Instant now) {
        // Approximate first-fetch as now - (currentInterval * observedFetches)
        // For tests we just use a sensible lower bound.
        return Duration.between(lastChangedAt, now).abs();
    }

    private static double durationDays(Duration d) {
        double ms = d.toMillis();
        return Math.max(0.001, ms / (1000.0 * 60 * 60 * 24));
    }
}
