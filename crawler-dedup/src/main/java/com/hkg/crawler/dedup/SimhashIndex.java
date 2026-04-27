package com.hkg.crawler.dedup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sublinear near-duplicate detection over 64-bit Simhash fingerprints,
 * implementing the Manku-Jain-Das partition-index trick (WWW 2007).
 *
 * <p><b>The pigeonhole insight:</b> two 64-bit fingerprints within
 * Hamming distance {@code k} must agree exactly on at least one of
 * {@code k+1} bit partitions. So instead of comparing each new
 * fingerprint to all stored fingerprints (O(N)), we maintain {@code k+1}
 * partition indexes — each keyed on a different 16-bit fixed block —
 * and look up exact matches on each partition's fixed block. Only those
 * candidates need the full Hamming compare. Lookup becomes sublinear in
 * the corpus size.
 *
 * <p>Default {@code k=3} (the Manku-Jain-Das operating threshold at
 * 8B-page scale): 4 partitions of 16 bits each. The 64-bit fingerprint is
 * split into 4 lanes — A (bits 0-15), B (bits 16-31), C (bits 32-47),
 * D (bits 48-63) — and each partition uses one lane as its fixed key.
 *
 * <p>Each partition is a {@code ConcurrentHashMap<Integer, Set<Long>>}
 * mapping a 16-bit lane value to the set of fingerprints sharing that
 * lane value. Lookup queries all 4 partitions for the candidate URL's
 * lane values and unions the result.
 *
 * <p>Memory: ~16 bytes/fingerprint × 4 partitions = ~64 bytes overhead
 * per stored fingerprint. At 10⁹ fingerprints, that's 64 GB — needs a
 * disk-backed implementation (RocksDB) at production scale; this
 * in-memory implementation is for the single-shard case and tests.
 */
public final class SimhashIndex {

    /** Default Hamming threshold per Manku-Jain-Das at 8B pages. */
    public static final int DEFAULT_K = 3;

    private final int k;
    private final int partitionCount;            // = k + 1
    private final List<ConcurrentHashMap<Integer, Set<Long>>> partitions;

    /** Per-partition lane extractors. lane[i] returns the 16-bit value at partition i. */
    private final int[] laneShifts;

    public SimhashIndex() {
        this(DEFAULT_K);
    }

    public SimhashIndex(int k) {
        if (k < 1 || k > 7) {
            throw new IllegalArgumentException("k must be in [1, 7]; got " + k);
        }
        this.k = k;
        this.partitionCount = k + 1;
        this.partitions = new ArrayList<>(partitionCount);
        this.laneShifts = new int[partitionCount];
        // Distribute (k+1) 16-bit lanes across the 64 bits.
        // For k=3 (4 partitions), shifts are 0, 16, 32, 48.
        int laneWidth = 64 / partitionCount;
        for (int i = 0; i < partitionCount; i++) {
            this.laneShifts[i] = i * laneWidth;
            this.partitions.add(new ConcurrentHashMap<>());
        }
    }

    /** Add a fingerprint to all {@link #partitionCount} partitions. */
    public void add(long fingerprint) {
        for (int i = 0; i < partitionCount; i++) {
            int lane = laneAt(fingerprint, i);
            partitions.get(i).computeIfAbsent(lane,
                key -> ConcurrentHashMap.newKeySet()).add(fingerprint);
        }
    }

    /**
     * Find the union of candidate fingerprints across all partitions
     * that share at least one lane with {@code fingerprint}.
     */
    public Set<Long> findCandidates(long fingerprint) {
        Set<Long> candidates = new HashSet<>();
        for (int i = 0; i < partitionCount; i++) {
            int lane = laneAt(fingerprint, i);
            Set<Long> bucket = partitions.get(i).get(lane);
            if (bucket != null) candidates.addAll(bucket);
        }
        candidates.remove(fingerprint);   // exclude self
        return candidates;
    }

    /**
     * Find the closest near-duplicate (smallest Hamming distance) within
     * threshold {@code k}. Returns empty if no fingerprint within
     * distance {@code k} exists.
     */
    public Optional<Long> findNearestNearDup(long fingerprint) {
        Set<Long> candidates = findCandidates(fingerprint);
        long best = 0;
        int bestDistance = Integer.MAX_VALUE;
        boolean found = false;
        for (long candidate : candidates) {
            int distance = Long.bitCount(fingerprint ^ candidate);
            if (distance <= k && distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                found = true;
            }
        }
        return found ? Optional.of(best) : Optional.empty();
    }

    /**
     * Is {@code fingerprint} within Hamming distance {@code k} of any
     * fingerprint already in the index?
     */
    public boolean hasNearDuplicate(long fingerprint) {
        return findNearestNearDup(fingerprint).isPresent();
    }

    /** Total number of distinct fingerprints stored. */
    public int size() {
        Set<Long> all = new HashSet<>();
        for (Map<Integer, Set<Long>> p : partitions) {
            for (Set<Long> bucket : p.values()) all.addAll(bucket);
        }
        return all.size();
    }

    public int k()                { return k; }
    public int partitionCount()   { return partitionCount; }

    // ---- internals -----------------------------------------------------

    private int laneAt(long fingerprint, int partitionIndex) {
        int shift = laneShifts[partitionIndex];
        return (int) ((fingerprint >>> shift) & 0xffffL);
    }
}
