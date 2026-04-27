package com.hkg.crawler.importance;

import com.hkg.crawler.common.CanonicalUrl;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Online Page Importance Computation (Abiteboul-Preda-Cobena, WWW 2003).
 *
 * <p>The algorithm runs <em>during</em> the crawl, avoiding the
 * staleness problem of batch PageRank. On each visit:
 *
 * <ol>
 *   <li>{@code history[u] += cash[u]} — the importance estimate
 *       accumulates this visit's cash.</li>
 *   <li>For each outlink {@code v}: {@code cash[v] += cash[u]/outdegree(u)}
 *       — distribute equally.</li>
 *   <li>{@code cash[u] = 0} — zero the visited URL's cash.</li>
 * </ol>
 *
 * <p>Per-visit cost: {@code O(outdegree(u))}. No global iteration.
 * Convergence properties are studied in the paper; for our purposes the
 * online {@code history[u]} is a fresh-by-construction proxy for
 * PageRank-equivalent importance.
 *
 * <p>Initial cash distribution: each URL starts with the same cash on
 * first observation (default 1.0). New URLs discovered via outlinks
 * receive cash from their visitor without an initial allocation —
 * importance accumulates entirely via the link graph.
 *
 * <p>Storage: in-memory {@code ConcurrentHashMap} for the single-shard
 * setup. At billion-URL scale this is replaced by a Cassandra-backed
 * store (Phase 3); the interface stays the same.
 */
public final class OpicComputer {

    private static final double DEFAULT_INITIAL_CASH = 1.0;

    private final double initialCash;
    private final ConcurrentMap<CanonicalUrl, OpicState> states = new ConcurrentHashMap<>();

    public OpicComputer() {
        this(DEFAULT_INITIAL_CASH);
    }

    public OpicComputer(double initialCash) {
        if (initialCash <= 0) throw new IllegalArgumentException("initialCash must be > 0");
        this.initialCash = initialCash;
    }

    /**
     * Add a seed URL with initial cash. Idempotent: re-adding an
     * existing URL doesn't reset its state.
     */
    public OpicState seed(CanonicalUrl url) {
        return states.computeIfAbsent(url, u -> new OpicState(u, initialCash));
    }

    /**
     * Process a visit to {@code visited} with the given outlinks.
     * Updates {@code visited.history}, distributes cash to outlinks,
     * zeros {@code visited.cash}. New outlinks are auto-seeded with
     * {@code 0.0} cash (they receive the distributed share but no
     * initial allocation; importance accumulates from inbound links).
     *
     * <p>Returns the cash that was distributed (i.e., the source URL's
     * cash before zeroing).
     */
    public synchronized double visit(CanonicalUrl visited, Collection<CanonicalUrl> outlinks) {
        OpicState src = states.computeIfAbsent(visited, u -> new OpicState(u, initialCash));
        double snapshot = src.recordVisitAndZeroCash();
        if (snapshot > 0 && !outlinks.isEmpty()) {
            double share = snapshot / outlinks.size();
            for (CanonicalUrl target : outlinks) {
                OpicState dst = states.computeIfAbsent(target, u -> new OpicState(u, 0.0));
                dst.receiveCash(share);
            }
        }
        return snapshot;
    }

    public Optional<OpicState> stateOf(CanonicalUrl url) {
        return Optional.ofNullable(states.get(url));
    }

    public double historyOf(CanonicalUrl url) {
        OpicState s = states.get(url);
        return s == null ? 0.0 : s.history();
    }

    public double cashOf(CanonicalUrl url) {
        OpicState s = states.get(url);
        return s == null ? 0.0 : s.cash();
    }

    /** Top-N URLs by accumulated history score. */
    public List<OpicState> topByHistory(int n) {
        return states.values().stream()
            .sorted(Comparator.comparingDouble(OpicState::history).reversed())
            .limit(n)
            .collect(Collectors.toList());
    }

    public int size() { return states.size(); }
}
