package com.hkg.crawler.dedup;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory Bloom filter, sized via the optimal-k formula:
 *
 * <pre>
 *   m ≈ -n × ln(f) / (ln 2)²       // bit array size
 *   k = (m / n) × ln 2              // optimal hash count
 * </pre>
 *
 * <p>Engineering rules of thumb (per the blog's §9 Dedup Service):
 * <ul>
 *   <li>1% FPR → ~9.6 bits per element</li>
 *   <li>0.1% FPR → ~14.4 bits per element</li>
 *   <li>10⁹ URLs at 1% FPR → 1.2 GB in memory</li>
 * </ul>
 *
 * <p>We use double hashing (Kirsch–Mitzenmacher 2006) to derive {@code k}
 * indices from two base hashes — much cheaper than computing {@code k}
 * independent hash functions. Base hashes are MurmurHash3-style 64-bit
 * mixes of FNV-1a and DJB-2 (no external dep).
 *
 * <p><b>Important caveat from §9:</b> single-tier Bloom is wrong for URL
 * dedup — false positives silently drop real URLs and erase coverage.
 * Always pair with an exact-set fallback ({@link TwoTierUrlDedup}).
 *
 * <p>Thread-safe for {@link #add} and {@link #mightContain} via the
 * underlying {@link BitSet}'s synchronized operations through this
 * class's monitor; not lock-free.
 */
public final class BloomFilter {

    private final BitSet bits;
    private final int    m;          // total bits
    private final int    k;          // hash function count
    private final long   capacity;   // designed for n elements
    private final double targetFpr;
    private final AtomicLong addCount = new AtomicLong();

    private BloomFilter(int m, int k, long capacity, double targetFpr) {
        this.bits      = new BitSet(m);
        this.m         = m;
        this.k         = k;
        this.capacity  = capacity;
        this.targetFpr = targetFpr;
    }

    /**
     * Construct a Bloom filter sized for {@code n} elements at
     * {@code targetFpr} false-positive rate.
     *
     * @throws IllegalArgumentException if {@code n < 1} or {@code targetFpr}
     *         is not in (0, 1)
     */
    public static BloomFilter create(long n, double targetFpr) {
        if (n < 1) throw new IllegalArgumentException("n must be ≥ 1");
        if (targetFpr <= 0 || targetFpr >= 1) {
            throw new IllegalArgumentException("targetFpr must be in (0, 1)");
        }
        // m = ceil(-n * ln(f) / (ln 2)^2)
        double ln2     = Math.log(2);
        double mDouble = -n * Math.log(targetFpr) / (ln2 * ln2);
        if (mDouble > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "filter would exceed " + Integer.MAX_VALUE + " bits");
        }
        int m = Math.max(8, (int) Math.ceil(mDouble));
        // k = round((m/n) * ln 2)
        int k = Math.max(1, (int) Math.round(((double) m / n) * ln2));
        return new BloomFilter(m, k, n, targetFpr);
    }

    /** Add an element. Returns {@code true} iff at least one bit was set. */
    public synchronized boolean add(String element) {
        long h1 = hashFnv1a(element);
        long h2 = hashDjb2(element);
        boolean changed = false;
        for (int i = 0; i < k; i++) {
            int idx = index(h1, h2, i);
            if (!bits.get(idx)) {
                bits.set(idx);
                changed = true;
            }
        }
        if (changed) addCount.incrementAndGet();
        return changed;
    }

    /**
     * Probabilistic membership query. Returns {@code true} iff every
     * one of the {@code k} bits is set. False positives possible
     * (rate ≈ {@link #estimatedFalsePositiveRate()}); false negatives
     * never.
     */
    public synchronized boolean mightContain(String element) {
        long h1 = hashFnv1a(element);
        long h2 = hashDjb2(element);
        for (int i = 0; i < k; i++) {
            if (!bits.get(index(h1, h2, i))) return false;
        }
        return true;
    }

    /**
     * Estimated current false-positive rate based on observed fill.
     * Uses the formula {@code (1 - e^(-k*adds/m))^k}.
     */
    public double estimatedFalsePositiveRate() {
        long adds = addCount.get();
        if (adds == 0) return 0;
        double exponent = -((double) k * adds) / m;
        return Math.pow(1 - Math.exp(exponent), k);
    }

    public int    bitCount()     { return m; }
    public int    hashCount()    { return k; }
    public long   capacity()     { return capacity; }
    public double targetFpr()    { return targetFpr; }
    public long   addedCount()   { return addCount.get(); }

    /** Bytes-on-the-heap estimate (BitSet stores 64 bits per long). */
    public long approximateSizeBytes() {
        return (long) Math.ceil(m / 8.0);
    }

    // ---- hashing -------------------------------------------------------

    private int index(long h1, long h2, int i) {
        long combined = h1 + (long) i * h2;
        // Force non-negative, fold to bit-array range.
        int idx = (int) ((combined & 0x7fffffffffffffffL) % m);
        return idx;
    }

    /** 64-bit FNV-1a over UTF-8 bytes. */
    private static long hashFnv1a(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Bernstein DJB-2 (×33) over UTF-8 bytes, widened to 64 bits. */
    private static long hashDjb2(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        long h = 5381;
        for (byte b : bytes) {
            h = ((h << 5) + h) + (b & 0xff);
        }
        return h;
    }
}
