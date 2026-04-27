package com.hkg.crawler.parser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 64-bit Charikar Simhash with weighted token features.
 *
 * <p>Algorithm (Charikar 2002, scaled to web by Manku-Jain-Das 2007):
 * <ol>
 *   <li>Tokenize the input into shingles (3-grams of words by default).</li>
 *   <li>Weight each shingle by frequency.</li>
 *   <li>Hash each shingle to a 64-bit value.</li>
 *   <li>For each of the 64 bit positions: if the hashed bit is 1, add the
 *       shingle's weight to the position's accumulator; if 0, subtract.</li>
 *   <li>The output's bit i is 1 iff the position's accumulator is positive.</li>
 * </ol>
 *
 * <p>Two documents with similar feature distributions produce
 * fingerprints with small Hamming distance. The blog's §9 uses k=3 as
 * the operating threshold for near-dup detection at billion-page scale.
 *
 * <p>This implementation strips boilerplate before fingerprinting (see
 * {@link BoilerplateStripper}) so templated headers/footers don't dominate
 * the signal — a known failure mode that produces false near-dup merges.
 */
public final class Simhash64 {

    private final int shingleSize;

    public Simhash64() {
        this(3);   // 3-token shingles by default
    }

    public Simhash64(int shingleSize) {
        if (shingleSize < 1) {
            throw new IllegalArgumentException("shingleSize must be ≥ 1");
        }
        this.shingleSize = shingleSize;
    }

    /** Compute the 64-bit fingerprint over plain-text input. */
    public long compute(String text) {
        Map<String, Integer> shingleWeights = shingleWith3GramWeights(text);
        long[] accumulator = new long[64];
        for (Map.Entry<String, Integer> e : shingleWeights.entrySet()) {
            long h = mix64(e.getKey());
            int weight = e.getValue();
            for (int bit = 0; bit < 64; bit++) {
                if (((h >>> bit) & 1L) == 1L) {
                    accumulator[bit] += weight;
                } else {
                    accumulator[bit] -= weight;
                }
            }
        }
        long fingerprint = 0L;
        for (int bit = 0; bit < 64; bit++) {
            if (accumulator[bit] > 0) {
                fingerprint |= (1L << bit);
            }
        }
        return fingerprint;
    }

    /** Hamming distance between two 64-bit fingerprints. */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * Are two fingerprints within Hamming distance {@code k}? At
     * Manku-Jain-Das's k=3, this is the operating definition of
     * "near-duplicate" for content-level dedup.
     */
    public static boolean isNearDuplicate(long a, long b, int k) {
        return hammingDistance(a, b) <= k;
    }

    // ---- internals -----------------------------------------------------

    private Map<String, Integer> shingleWith3GramWeights(String text) {
        String[] tokens = tokenize(text);
        Map<String, Integer> weights = new HashMap<>();
        if (tokens.length < shingleSize) {
            // Document too short for shingle size — fall back to single tokens.
            for (String t : tokens) {
                weights.merge(t, 1, Integer::sum);
            }
            return weights;
        }
        for (int i = 0; i <= tokens.length - shingleSize; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < shingleSize; j++) {
                if (j > 0) sb.append(' ');
                sb.append(tokens[i + j]);
            }
            weights.merge(sb.toString(), 1, Integer::sum);
        }
        return weights;
    }

    /**
     * Word tokenization: lowercase, split on whitespace and punctuation,
     * drop empty tokens. Sufficient for stable fingerprinting; not
     * intended as language-aware tokenization (that's the indexer's job).
     */
    private String[] tokenize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.split("[\\s\\p{Punct}]+");
    }

    /**
     * 64-bit hash mix — same FNV-1a / SplittableRandom-style mixer used
     * elsewhere in the codebase for consistency.
     */
    private long mix64(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        // Avalanche pass for better bit distribution.
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
