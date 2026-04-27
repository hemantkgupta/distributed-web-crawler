package com.hkg.crawler.importance;

import com.hkg.crawler.common.CanonicalUrl;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adaptive recrawl scheduler — Cho/Garcia-Molina change-rate estimation
 * combined with OPIC importance for priority ordering.
 *
 * <p>Per blog §10 Problem 5: there is no single optimal recrawl interval
 * independent of objective. We pick freshness, accept that stable pages
 * are revisited rarely, and let {@link RecrawlEntry}'s exponential
 * interval grow them up to {@link RecrawlEntry#MAX_INTERVAL} (30 days).
 *
 * <p>Storage: in-memory {@code ConcurrentHashMap} for the single-shard
 * setup. At billion-URL scale this is replaced by a Cassandra-backed
 * store (Phase 3) with a secondary index on {@code next_recrawl_time}
 * for the "URLs past their due time" query — but the interface here
 * stays the same.
 */
public final class RecrawlScheduler {

    private final ConcurrentMap<CanonicalUrl, RecrawlEntry> entries = new ConcurrentHashMap<>();
    private final OpicComputer opic;
    private final Duration initialInterval;

    public RecrawlScheduler(OpicComputer opic) {
        this(opic, Duration.ofDays(1));
    }

    public RecrawlScheduler(OpicComputer opic, Duration initialInterval) {
        this.opic = opic;
        this.initialInterval = initialInterval;
    }

    /** Register a URL for recrawl tracking on first fetch. */
    public RecrawlEntry register(CanonicalUrl url, Instant firstFetchedAt) {
        return entries.computeIfAbsent(url,
            u -> new RecrawlEntry(u, firstFetchedAt, initialInterval));
    }

    /**
     * Apply a fetch verdict. {@code contentChanged} is true iff the
     * page's content differs from the prior fetch (e.g., from comparing
     * SHA-256 of the body or Simhash distance). Auto-registers the URL
     * if not yet known.
     */
    public void recordFetch(CanonicalUrl url, boolean contentChanged, Instant now) {
        RecrawlEntry entry = register(url, now);
        entry.recordFetch(contentChanged, now);
    }

    /**
     * Return up to {@code limit} URLs whose {@code nextRecrawlTime} has
     * passed, ordered by priority (importance × log(1+λ̂)) descending.
     *
     * <p>This is the §10 "scheduler tick" query: every 60 seconds the
     * Frontier pulls the top-K overdue URLs and enqueues them at
     * URGENT_RECRAWL or HIGH_OPIC priority class.
     */
    public List<CanonicalUrl> dueRecrawlsByPriority(Instant now, int limit) {
        return overdueStream(now)
            .map(e -> new RankedEntry(e, e.priorityWithImportance(opic.historyOf(e.url()))))
            .sorted(Comparator.comparingDouble(RankedEntry::priority).reversed())
            .limit(limit)
            .map(r -> r.entry().url())
            .collect(Collectors.toList());
    }

    /** Total URLs currently overdue for recrawl. */
    public long overdueCount(Instant now) {
        return overdueStream(now).count();
    }

    public Optional<RecrawlEntry> entryFor(CanonicalUrl url) {
        return Optional.ofNullable(entries.get(url));
    }

    public int trackedUrlCount() { return entries.size(); }

    // ---- internals -----------------------------------------------------

    private Stream<RecrawlEntry> overdueStream(Instant now) {
        return entries.values().stream()
            .filter(e -> !e.nextRecrawlTime().isAfter(now));
    }

    private record RankedEntry(RecrawlEntry entry, double priority) {}
}
