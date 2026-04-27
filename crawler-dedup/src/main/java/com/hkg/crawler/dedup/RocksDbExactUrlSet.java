package com.hkg.crawler.dedup;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk-first exact URL set backed by RocksDB with **256 column-family
 * buckets** — the IRLbot DRUM lineage adapted to RocksDB's LSM.
 *
 * <p>Why bucket-sharded? Three reasons:
 * <ol>
 *   <li>Each bucket is independently compactable; a single hot host's
 *       URL space doesn't dominate compaction load.</li>
 *   <li>Bucket lookups parallelize trivially — the dedup verdict for
 *       N concurrent URLs distributes evenly across all 256 buckets.</li>
 *   <li>At billion-URL scale, per-bucket file sizes stay in the
 *       tens-of-GB range RocksDB compaction is designed for.</li>
 * </ol>
 *
 * <p>Bucketing key: {@code MurmurHash3(url) mod 256}. Computed via the
 * same FNV-1a mixer used elsewhere in the codebase for consistency.
 *
 * <p>The interface is identical to {@link InMemoryExactUrlSet}; callers
 * choose the implementation by RAM/disk budget.
 *
 * <p>Thread-safe: RocksDB handles concurrent reads and batched writes
 * natively. Not safe to open the same path from multiple processes
 * (RocksDB enforces this with a file lock).
 */
public final class RocksDbExactUrlSet implements ExactUrlSet {

    private static final int BUCKET_COUNT = 256;
    private static final byte[] PRESENCE_VALUE = new byte[] { 1 };

    static {
        RocksDB.loadLibrary();
    }

    private final DBOptions dbOptions;
    private final ColumnFamilyOptions cfOptions;
    private final RocksDB db;
    private final List<ColumnFamilyHandle> buckets;
    private final AtomicLong sizeEstimate = new AtomicLong();

    public RocksDbExactUrlSet(Path path) {
        try {
            Files.createDirectories(path);
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to create RocksDB dir " + path, e);
        }
        this.dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true);
        this.cfOptions = new ColumnFamilyOptions();

        // Build column-family descriptors — must include the default CF.
        List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>(BUCKET_COUNT + 1);
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));
        for (int i = 0; i < BUCKET_COUNT; i++) {
            cfDescriptors.add(new ColumnFamilyDescriptor(
                bucketName(i).getBytes(StandardCharsets.UTF_8), cfOptions));
        }

        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            this.db = RocksDB.open(dbOptions, path.toString(), cfDescriptors, handles);
        } catch (RocksDBException e) {
            cfOptions.close();
            dbOptions.close();
            throw new RuntimeException("failed to open RocksDB at " + path, e);
        }
        // Skip the default CF (handles[0]); keep only the 256 bucket CFs.
        this.buckets = handles.subList(1, handles.size());
    }

    @Override
    public boolean add(String url) {
        byte[] keyBytes = url.getBytes(StandardCharsets.UTF_8);
        int bucket = bucketFor(url);
        ColumnFamilyHandle cf = buckets.get(bucket);
        try {
            // Check existence first to maintain accurate add() boolean semantics.
            byte[] existing = db.get(cf, keyBytes);
            if (existing != null) return false;
            db.put(cf, keyBytes, PRESENCE_VALUE);
            sizeEstimate.incrementAndGet();
            return true;
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB add failed for " + url, e);
        }
    }

    @Override
    public boolean contains(String url) {
        byte[] keyBytes = url.getBytes(StandardCharsets.UTF_8);
        int bucket = bucketFor(url);
        ColumnFamilyHandle cf = buckets.get(bucket);
        try {
            return db.get(cf, keyBytes) != null;
        } catch (RocksDBException e) {
            throw new RuntimeException("RocksDB contains failed for " + url, e);
        }
    }

    /**
     * Best-effort size — counts URLs added via this instance. Does not
     * include URLs persisted by a previous process; for that, use
     * {@code rocksdb.estimate-num-keys} per CF (more expensive).
     */
    @Override
    public long size() {
        return sizeEstimate.get();
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle h : buckets) h.close();
        db.close();
        cfOptions.close();
        dbOptions.close();
    }

    // ---- internals -----------------------------------------------------

    private static int bucketFor(String url) {
        // FNV-1a 64-bit fold, then mod 256.
        byte[] bytes = url.getBytes(StandardCharsets.UTF_8);
        long h = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return (int) ((h & 0x7fffffffffffffffL) % BUCKET_COUNT);
    }

    private static String bucketName(int i) {
        return String.format("bucket_%03d", i);
    }
}
