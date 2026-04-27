package com.hkg.crawler.frontier;

import com.hkg.crawler.common.CanonicalUrl;
import com.hkg.crawler.common.FetchOutcome;
import com.hkg.crawler.common.Host;
import com.hkg.crawler.common.PriorityClass;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-process, in-memory implementation of {@link Frontier}.
 *
 * <p>Mercator-style two-tier scheduler:
 * <ul>
 *   <li>{@link #frontQueues} — one FIFO per {@link PriorityClass}.</li>
 *   <li>{@link #backQueues} — one FIFO per active host. Each is paired
 *       with a {@link HostState} in {@link #hostState}.</li>
 *   <li>{@link #readyHeap} — a min-heap of host queues keyed by
 *       {@code next_fetch_time}. Pop yields the soonest-ready host.</li>
 * </ul>
 *
 * <p>Heap entries are stamped with a generation counter so we can lazily
 * invalidate stale entries when a host's clock advances. Re-inserting
 * carries a fresh generation; on pop, we discard entries whose generation
 * doesn't match the host's current one.
 *
 * <p>This implementation is single-threaded internally — all public
 * methods are {@code synchronized}. A future implementation will use
 * fine-grained locking once we add multi-threaded fetcher pools.
 *
 * <p>Persistence is out of scope for this checkpoint; see Checkpoint 3
 * for RocksDB-backed durability.
 */
public final class InMemoryFrontier implements Frontier {

    private final Map<PriorityClass, Deque<FrontierUrl>> frontQueues =
        new EnumMap<>(PriorityClass.class);

    /** Insertion-ordered to make per-host iteration deterministic in tests. */
    private final Map<Host, Deque<FrontierUrl>> backQueues = new LinkedHashMap<>();

    private final Map<Host, HostState> hostState = new HashMap<>();

    /**
     * Per-host generation counter. Incremented on each heap re-insert
     * for the host. {@link #readyHeap} entries with stale generations
     * are skipped on pop.
     */
    private final Map<Host, Long> hostGeneration = new HashMap<>();

    private final PriorityQueue<HeapEntry> readyHeap =
        new PriorityQueue<>(Comparator
            .comparing((HeapEntry e) -> e.nextFetchTime)
            .thenComparing(e -> e.host.value()));

    /** Tie-breaker counter so heap entries are deterministically ordered. */
    private final AtomicLong sequenceCounter = new AtomicLong();

    public InMemoryFrontier() {
        for (PriorityClass cls : PriorityClass.values()) {
            frontQueues.put(cls, new ArrayDeque<>());
        }
    }

    @Override
    public synchronized void enqueue(FrontierUrl url) {
        // Front-queue path: the selector decides when to drain into back queues.
        frontQueues.get(url.priorityClass()).addLast(url);
        // Eagerly drain so single-host workloads see immediate progress.
        drainFrontQueueFor(url.url().host(), Instant.now());
    }

    @Override
    public synchronized Optional<ClaimedUrl> claimNext(Instant now) {
        while (!readyHeap.isEmpty()) {
            HeapEntry entry = readyHeap.peek();
            // Skip stale entries (host's generation has moved on).
            Long currentGen = hostGeneration.get(entry.host);
            if (currentGen == null || currentGen != entry.generation) {
                readyHeap.poll();
                continue;
            }
            // Skip quarantined hosts.
            HostState state = hostState.get(entry.host);
            if (state == null || state.isQuarantined()) {
                readyHeap.poll();
                continue;
            }
            // Not yet ready in wall-clock time? Caller should wait.
            if (entry.nextFetchTime.isAfter(now)) {
                return Optional.empty();
            }
            // Claim: pop, update state, return.
            readyHeap.poll();
            Deque<FrontierUrl> queue = backQueues.get(entry.host);
            if (queue == null || queue.isEmpty()) {
                // Empty back queue — try refilling from front, otherwise drop.
                refillBackQueueFromFront(entry.host);
                queue = backQueues.get(entry.host);
                if (queue == null || queue.isEmpty()) {
                    continue;
                }
            }
            FrontierUrl head = queue.removeFirst();
            state.decrementBackQueueDepth();
            state.recordClaim(now);
            // Re-insert if still has work.
            if (!queue.isEmpty()) {
                pushHeap(entry.host);
            } else {
                // Try to pull more from front; if successful, push.
                refillBackQueueFromFront(entry.host);
                if (!queue.isEmpty()) {
                    pushHeap(entry.host);
                }
            }
            return Optional.of(new ClaimedUrl(
                head.url(),
                entry.host,
                now,
                state.effectiveDelay()
            ));
        }
        return Optional.empty();
    }

    @Override
    public synchronized void reportVerdict(CanonicalUrl url, FetchOutcome outcome, Instant now) {
        Host host = url.host();
        HostState state = hostState.get(host);
        if (state == null) return;   // verdict for a host we no longer track; ignore
        state.recordVerdict(outcome, now);
        // Re-insert with new clock if there's still work for this host.
        Deque<FrontierUrl> queue = backQueues.get(host);
        if (queue != null && !queue.isEmpty()) {
            pushHeap(host);
        }
    }

    @Override
    public synchronized void quarantineHost(Host host) {
        HostState state = hostState.get(host);
        if (state != null) state.setQuarantined(true);
    }

    @Override
    public synchronized void releaseHost(Host host) {
        HostState state = hostState.get(host);
        if (state == null) return;
        state.setQuarantined(false);
        Deque<FrontierUrl> queue = backQueues.get(host);
        if (queue != null && !queue.isEmpty()) {
            pushHeap(host);
        }
    }

    @Override
    public synchronized FrontierStats stats() {
        Map<PriorityClass, Integer> sizes = new EnumMap<>(PriorityClass.class);
        int totalFront = 0;
        for (var e : frontQueues.entrySet()) {
            sizes.put(e.getKey(), e.getValue().size());
            totalFront += e.getValue().size();
        }
        int totalBack = backQueues.values().stream().mapToInt(Deque::size).sum();
        int activeHosts = (int) hostState.values().stream().filter(s -> !s.isQuarantined()).count();
        int quarantined = (int) hostState.values().stream().filter(HostState::isQuarantined).count();

        // Walk readyHeap to find earliest non-stale entry and count ready hosts.
        Optional<Instant> earliest = Optional.empty();
        int ready = 0;
        for (HeapEntry entry : readyHeap) {
            Long gen = hostGeneration.get(entry.host);
            if (gen == null || gen != entry.generation) continue;
            HostState s = hostState.get(entry.host);
            if (s == null || s.isQuarantined()) continue;
            ready++;
            if (earliest.isEmpty() || entry.nextFetchTime.isBefore(earliest.get())) {
                earliest = Optional.of(entry.nextFetchTime);
            }
        }
        return new FrontierStats(totalFront, totalBack, sizes,
            activeHosts, ready, quarantined, earliest);
    }

    // ---- internals ---------------------------------------------------

    /**
     * Move the URL at the head of any front queue whose host matches
     * {@code host} into that host's back queue. Invariant called from
     * the enqueue path so single-host workloads see the URL immediately.
     */
    private void drainFrontQueueFor(Host host, Instant now) {
        for (Deque<FrontierUrl> q : frontQueues.values()) {
            for (Iterator<FrontierUrl> it = q.iterator(); it.hasNext(); ) {
                FrontierUrl candidate = it.next();
                if (candidate.url().host().equals(host)) {
                    it.remove();
                    appendToBackQueue(candidate, now);
                    return;
                }
            }
        }
    }

    /**
     * Pull URLs from front queues into the given host's back queue if
     * any exist for that host. Used after a claim empties a back queue.
     */
    private void refillBackQueueFromFront(Host host) {
        Instant now = Instant.now();
        for (Deque<FrontierUrl> q : frontQueues.values()) {
            for (Iterator<FrontierUrl> it = q.iterator(); it.hasNext(); ) {
                FrontierUrl candidate = it.next();
                if (candidate.url().host().equals(host)) {
                    it.remove();
                    appendToBackQueue(candidate, now);
                    return;
                }
            }
        }
    }

    private void appendToBackQueue(FrontierUrl url, Instant now) {
        Host host = url.url().host();
        boolean wasEmpty = !backQueues.containsKey(host)
            || backQueues.get(host).isEmpty();
        backQueues.computeIfAbsent(host, h -> new ArrayDeque<>()).addLast(url);
        HostState state = hostState.computeIfAbsent(host, h -> new HostState(h, now));
        state.incrementBackQueueDepth();
        if (wasEmpty) {
            // First URL for an idle host → eligible immediately.
            state.recordClaim(now.minus(state.effectiveDelay()));   // i.e. nextFetchTime = now
            pushHeap(host);
        }
    }

    /**
     * Insert a fresh heap entry for {@code host} reflecting its current
     * {@code next_fetch_time}. Bumps the host's generation so stale
     * entries from prior insertions are skipped on pop.
     */
    private void pushHeap(Host host) {
        HostState state = hostState.get(host);
        if (state == null) return;
        long gen = hostGeneration.merge(host, 1L, Long::sum);
        readyHeap.offer(new HeapEntry(host, state.nextFetchTime(), gen,
            sequenceCounter.incrementAndGet()));
    }

    /**
     * Take a serializable snapshot of the current Frontier state. Safe
     * to call concurrently with other operations (synchronized);
     * produces a point-in-time view.
     *
     * <p>The snapshot is the unit of persistence: it is what
     * {@code RocksDbFrontierStore} writes to disk every 10 seconds, and
     * what {@link #restore} reads back on startup.
     */
    public synchronized FrontierSnapshot snapshot(Instant now) {
        var hosts = new java.util.ArrayList<FrontierSnapshot.HostSnapshot>();
        for (HostState s : hostState.values()) {
            hosts.add(new FrontierSnapshot.HostSnapshot(
                s.host().value(),
                s.nextFetchTime().toEpochMilli(),
                s.crawlDelay().toMillis(),
                s.backoffFactor(),
                s.consecutiveErrors(),
                s.lastSuccessTime().toEpochMilli(),
                s.backQueueDepth(),
                s.isQuarantined()
            ));
        }
        var back = new java.util.ArrayList<FrontierSnapshot.BackQueueEntry>();
        for (var e : backQueues.entrySet()) {
            for (FrontierUrl u : e.getValue()) {
                back.add(new FrontierSnapshot.BackQueueEntry(
                    e.getKey().value(),
                    u.url().value(),
                    u.priorityClass().name(),
                    u.discoveredAt().toEpochMilli()
                ));
            }
        }
        var front = new java.util.ArrayList<FrontierSnapshot.FrontQueueEntry>();
        for (var e : frontQueues.entrySet()) {
            for (FrontierUrl u : e.getValue()) {
                front.add(new FrontierSnapshot.FrontQueueEntry(
                    e.getKey().name(),
                    u.url().value(),
                    u.discoveredAt().toEpochMilli()
                ));
            }
        }
        return new FrontierSnapshot(
            FrontierSnapshot.CURRENT_FORMAT_VERSION,
            now.toEpochMilli(),
            hosts, back, front
        );
    }

    /**
     * Reconstruct an {@code InMemoryFrontier} from a previously taken
     * snapshot. Used at startup to recover state after a process restart.
     *
     * <p>The heap is rebuilt by walking the host states; URL ordering
     * within each back queue is preserved via the snapshot's iteration
     * order.
     */
    public static InMemoryFrontier restore(FrontierSnapshot snap) {
        if (snap.formatVersion() != FrontierSnapshot.CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported snapshot format version: " + snap.formatVersion());
        }
        InMemoryFrontier f = new InMemoryFrontier();
        // First, restore host state directly (without going through enqueue,
        // which would reset clocks).
        for (FrontierSnapshot.HostSnapshot hs : snap.hosts()) {
            Host host = Host.of(hs.hostName());
            HostState s = new HostState(host, Instant.ofEpochMilli(hs.nextFetchTimeEpochMs()));
            s.setCrawlDelay(java.time.Duration.ofMillis(hs.crawlDelayMs()));
            // Walk to the right backoff via reflection-free repeated verdicts
            // is impractical; we directly set it via the restoredFromSnapshot path:
            applyBackoffFactor(s, hs.backoffFactor());
            for (int i = 0; i < hs.consecutiveErrors(); i++) {
                // Replaying errors here would over-multiply backoff; we already
                // set the correct factor above. Use a no-op that just bumps
                // the counter:
                applyConsecutiveError(s);
            }
            if (hs.quarantined()) s.setQuarantined(true);
            f.hostState.put(host, s);
        }
        // Restore back queues.
        for (FrontierSnapshot.BackQueueEntry be : snap.backQueueUrls()) {
            Host host = Host.of(be.hostName());
            FrontierUrl url = new FrontierUrl(
                com.hkg.crawler.common.CanonicalUrl.of(be.url()),
                com.hkg.crawler.common.PriorityClass.valueOf(be.priorityClass()),
                Instant.ofEpochMilli(be.discoveredAtEpochMs())
            );
            f.backQueues.computeIfAbsent(host, h -> new ArrayDeque<>()).addLast(url);
        }
        // Restore front queues.
        for (FrontierSnapshot.FrontQueueEntry fe : snap.frontQueueUrls()) {
            FrontierUrl url = new FrontierUrl(
                com.hkg.crawler.common.CanonicalUrl.of(fe.url()),
                com.hkg.crawler.common.PriorityClass.valueOf(fe.priorityClass()),
                Instant.ofEpochMilli(fe.discoveredAtEpochMs())
            );
            f.frontQueues.get(com.hkg.crawler.common.PriorityClass.valueOf(fe.priorityClass()))
                .addLast(url);
        }
        // Rebuild the heap from non-quarantined hosts with non-empty back queues.
        for (Host host : f.backQueues.keySet()) {
            if (!f.backQueues.get(host).isEmpty()) {
                HostState s = f.hostState.get(host);
                if (s != null && !s.isQuarantined()) {
                    f.pushHeap(host);
                }
            }
        }
        return f;
    }

    /**
     * Test-only access to a host's state for assertions. Prefer
     * {@link #stats()} for production observability.
     */
    public synchronized Optional<HostState> hostStateFor(Host host) {
        return Optional.ofNullable(hostState.get(host));
    }

    // Helpers used by restore() to set HostState fields without going
    // through the verdict path (which would advance the clock).
    private static void applyBackoffFactor(HostState s, double factor) {
        // The cleanest way without exposing setters is to drive the state
        // via successes/errors. For restore, we'd rather just trust the
        // snapshot and inject. We use a package-private setter on HostState.
        s.restoreBackoffFactor(factor);
    }
    private static void applyConsecutiveError(HostState s) {
        s.restoreIncrementConsecutiveErrors();
    }

    /**
     * Test-only: how many URLs are currently sitting in {@code host}'s
     * back queue (i.e., not in front queues).
     */
    public synchronized int backQueueDepth(Host host) {
        Deque<FrontierUrl> q = backQueues.get(host);
        return q == null ? 0 : q.size();
    }

    /** Heap entry; immutable. */
    private record HeapEntry(Host host, Instant nextFetchTime, long generation, long sequence) {}
}
